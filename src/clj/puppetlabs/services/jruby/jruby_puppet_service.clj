(ns puppetlabs.services.jruby.jruby-puppet-service
  (:require [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.services.jruby.jruby-puppet-core :as core]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [slingshot.slingshot :as sling]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; This service uses TK's normal config service instead of the
;; PuppetServerConfigService.  This is because that service depends on this one.

(trapperkeeper/defservice jruby-puppet-pooled-service
                          jruby/JRubyPuppetService
                          [[:ConfigService get-config]
                           [:ShutdownService shutdown-on-error]
                           [:PuppetProfilerService get-profiler]]
  (init
    [this context]
    (let [config            (core/initialize-config (get-config))
          service-id        (tk-services/service-id this)
          agent-shutdown-fn (partial shutdown-on-error service-id)
          profiler          (get-profiler)]
      (core/verify-config-found! config)
      (log/info "Initializing the JRuby service")
      (core/add-facter-jar-to-system-classloader (:ruby-load-path config))
      (let [pool-context (core/create-pool-context config profiler agent-shutdown-fn)]
        (jruby-agents/send-prime-pool! pool-context)
        (-> context
            (assoc :pool-context pool-context)
            (assoc :borrow-timeout (:borrow-timeout config))))))

  (borrow-instance
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          borrow-timeout (:borrow-timeout (tk-services/service-context this))]
      (core/borrow-from-pool-with-timeout pool-context borrow-timeout)))

  (return-instance
    [this jruby-instance]
    (core/return-to-pool jruby-instance))

  (free-instance-count
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))
          pool         (core/get-pool pool-context)]
      (core/free-instance-count pool)))

  (mark-environment-expired!
    [this env-name]
    (let [pool-context (:pool-context (tk-services/service-context this))]
      (core/mark-environment-expired! pool-context env-name)))

  (mark-all-environments-expired!
    [this]
    (let [pool-context (:pool-context (tk-services/service-context this))]
      (core/mark-all-environments-expired! pool-context)))

  (flush-jruby-pool!
    [this]
    (let [service-context (tk-services/service-context this)
          {:keys [pool-context]} service-context]
      (jruby-agents/send-flush-pool! pool-context))))

(defmacro with-jruby-puppet
  "Encapsulates the behavior of borrowing and returning an instance of
  JRubyPuppet.  Example usage:

  (let [jruby-service (get-service :JRubyPuppetService)]
    (with-jruby-puppet
      jruby-puppet
      jruby-service
      (do-something-with-a-jruby-puppet-instance jruby-puppet)))

  Will throw an IllegalStateException if borrowing an instance of
  JRubyPuppet times out."
  [jruby-puppet jruby-service & body]
  `(loop [pool-instance# (jruby/borrow-instance ~jruby-service)]
     (if (nil? pool-instance#)
       (sling/throw+
         {:type    ::jruby-timeout
          :message (str "Attempt to borrow a JRuby instance from the pool "
                        "timed out; Puppet Server is temporarily overloaded. If "
                        "you get this error repeatedly, your server might be "
                        "misconfigured or trying to serve too many agent nodes. "
                        "Check Puppet Server settings: "
                        "jruby-puppet.max-active-instances.")}))
     (if (jruby-schemas/retry-poison-pill? pool-instance#)
       (do
         (jruby-core/return-to-pool pool-instance#)
         (recur (jruby/borrow-instance ~jruby-service)))
       (let [~jruby-puppet (:jruby-puppet pool-instance#)]
         (try
           ~@body
           (finally
             (jruby/return-instance ~jruby-service pool-instance#)))))))
