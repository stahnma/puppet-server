(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.kitchensink.classpath :as ks-classpath]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [puppetlabs.services.jruby.jruby-puppet-internal :as jruby-internal]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (puppetlabs.services.jruby.jruby_puppet_schemas JRubyPuppetInstance)
           (com.puppetlabs.puppetserver PuppetProfiler)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

(def default-http-connect-timeout
  "The default number of milliseconds that the client will wait for a connection
  to be established. Currently set to 2 minutes."
  (* 2 60 1000))

(def default-http-socket-timeout
  "The default number of milliseconds that the client will allow for no data to
  be available on the socket. Currently set to 20 minutes."
  (* 20 60 1000))

(def default-master-conf-dir
  "/etc/puppetlabs/puppet")

(def default-master-code-dir
  "/etc/puppetlabs/code")

(def default-master-log-dir
  "/var/log/puppetlabs/puppetserver")

(def default-master-run-dir
  "/var/run/puppetlabs/puppetserver")

(def default-master-var-dir
  "/opt/puppetlabs/server/data/puppetserver")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn default-pool-size
  "Calculate the default size of the JRuby pool, based on the number of cpus."
  [num-cpus]
  (->> (- num-cpus 1)
       (max 1)
       (min 4)))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRubyPuppet pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (jruby-internal/get-pool context))

(schema/defn ^:always-validate
  pool->vec :- [JRubyPuppetInstance]
  [context :- jruby-schemas/PoolContext]
  (-> (get-pool context)
      .iterator
      iterator-seq
      vec))

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyPuppetConfig
  [config :- {schema/Keyword schema/Any}]
  (-> (get-in config [:jruby-puppet])
    (assoc :http-client-ssl-protocols
      (get-in config [:http-client :ssl-protocols]))
    (assoc :http-client-cipher-suites
      (get-in config [:http-client :cipher-suites]))
    (assoc :http-client-connect-timeout-milliseconds
      (get-in config [:http-client :connect-timeout-milliseconds]
        default-http-connect-timeout))
    (assoc :http-client-idle-timeout-milliseconds
      (get-in config [:http-client :idle-timeout-milliseconds]
        default-http-socket-timeout))
    (update-in [:borrow-timeout] #(or % default-borrow-timeout))
    (update-in [:master-conf-dir] #(or % default-master-conf-dir))
    (update-in [:master-var-dir] #(or % default-master-var-dir))
    (update-in [:master-code-dir] #(or % default-master-code-dir))
    (update-in [:master-run-dir] #(or % default-master-run-dir))
    (update-in [:master-log-dir] #(or % default-master-log-dir))
    (update-in [:max-active-instances] #(or % (default-pool-size (ks/num-cpus))))
    (update-in [:max-requests-per-instance] #(or % 0))))

(def facter-jar
  "Well-known name of the facter jar file"
  "facter.jar")

(schema/defn ^:always-validate
  add-facter-jar-to-system-classloader
  "Searches the ruby load path for a file whose name matches that of the
  facter jar file.  The first one found is added to the system classloader's
  classpath.  If no match is found, an info message is written to the log
  but no failure is returned"
  [ruby-load-path :- (schema/both (schema/pred vector?) [schema/Str]) ]
  (if-let [facter-jar (first
                        (filter fs/exists?
                          (map #(fs/file % facter-jar) ruby-load-path)))]
    (do
      (log/debugf "Adding facter jar to classpath from: %s" facter-jar)
      (ks-classpath/add-classpath facter-jar))
    (log/info "Facter jar not found in ruby load path")))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRubyPuppet pool context with an empty pool. Once the JRubyPuppet
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)
   agent-shutdown-fn :- (schema/pred ifn?)]
  {:config                config
   :profiler              profiler
   :pool-agent            (jruby-agents/pool-agent agent-shutdown-fn)
   :flush-instance-agent  (jruby-agents/pool-agent agent-shutdown-fn)
   :pool-state            (atom (jruby-internal/create-pool-from-config config))})

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyPuppet instances available in the pool."
  [pool :- jruby-schemas/pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  instance-state :- jruby-schemas/JRubyInstanceState
  "Get the state metadata for a JRubyPuppet instance."
  [jruby-puppet :- (schema/pred jruby-schemas/jruby-puppet-instance?)]
  @(:state jruby-puppet))

(schema/defn ^:always-validate
  mark-environment-expired!
  [context :- jruby-schemas/PoolContext
   env-name :- schema/Str]
  (doseq [jruby-instance (pool->vec context)]
    (-> jruby-instance
      :environment-registry
      (puppet-env/mark-environment-expired! env-name))))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  [context :- jruby-schemas/PoolContext]
  (doseq [jruby-instance (pool->vec context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrRetry
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext]
  (jruby-internal/borrow-from-pool
    pool-context))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (jruby-internal/borrow-from-pool-with-timeout
    pool-context
    timeout))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrRetry]
  (jruby-internal/return-to-pool instance))

(schema/defn ^:always-validate cli-ruby! :- jruby-schemas/JRubyMainStatus
  "Run JRuby as though native `ruby` were invoked with args on the CLI"
  [config :- {schema/Keyword schema/Any}
   args :- [schema/Str]]
  (let [main (jruby-internal/new-main (initialize-config config))
        argv (into-array String (concat ["-rjar-dependencies"] args))]
    (.run main argv)))

(schema/defn ^:always-validate cli-run! :- (schema/maybe jruby-schemas/JRubyMainStatus)
  "Run a JRuby CLI command, e.g. gem, irb, etc..."
  [config :- {schema/Keyword schema/Any}
   command :- schema/Str
   args :- [schema/Str]]
  (let [bin-dir "META-INF/jruby.home/bin"
        load-path (format "%s/%s" bin-dir command)
        url (io/resource load-path (.getClassLoader org.jruby.Main))]
    (if url
      (cli-ruby! config
        (concat ["-e" (format "load '%s'" url) "--"] args))
      (log/errorf "command %s could not be found in %s" command bin-dir))))
