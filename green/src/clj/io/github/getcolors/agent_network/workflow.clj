(ns io.github.getcolors.agent-network.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.agent-network.ssh :as ssh]
            [io.github.getcolors.agent-network.ssh-config :as ssh-config]
            [io.github.getcolors.agent-network.tools :as tools]
            [io.github.getcolors.agent-network.validate :as validate]))

(def defaults {:provider-compute "vultr" :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "The compute stage's applied `params`, or nil when no state is readable. The
  create matrix keys on this best-effort read: an unreadable state (a fresh
  clone, a missing backend) counts as absent."
  [opts]
  (try (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                             (tools/backend-credential-env opts))
               :params walk/keywordize-keys)
       (catch Exception _ nil)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts event)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate
          ;; The machine key's create matrix and the Vultr preflight run before
          ;; any template is rendered: an unowned key on disk or at the provider
          ;; stops the run while stopping is still free. Delete fills the same
          ;; template values — a destroy renders before it destroys — but checks
          ;; nothing, because its key cleanup runs after the compute destroy.
          (fn [opts _ {:keys [event real?]}]
            (cond
              (and real? (= :delete event))
              (merge (ssh/with-machine-key opts)
                     (or (state-output opts) {})
                     {:green/exit 0})

              (and real? (= :create event))
              (let [opts (ssh/ensure-key! opts state-output)]
                (if (wf/failed? opts)
                  opts
                  (let [opts (ssh/preflight! (ssh/with-machine-key opts))
                        opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
                    (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

              :else
              (assoc (ssh/with-machine-key opts) :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :agent-network/start [start-step :agent-network/ansible]
      :agent-network/ansible [tools/ansible-step :agent-network/dns]
      ;; The `~/.ssh/config` block goes before the destroy, the opposite of the
      ;; keypair below. A block that outlives its host is stale but harmless; a
      ;; key that predeceases its host locks the operator out of a machine that
      ;; still exists. Both orders are deliberate; see standards/ssh-config.md.
      :agent-network/dns [tools/dns-step :agent-network/ssh-config]
      :agent-network/ssh-config [tools/ansible-local-step :agent-network/infrastructure]
      :agent-network/infrastructure [tools/infrastructure-step :agent-network/ssh-cleanup]
      :agent-network/ssh-cleanup [ssh/cleanup-step])
    (case step
      :agent-network/start [start-step :agent-network/infrastructure]
      ;; After compute, which is where the address first exists, and before the
      ;; stage that converges the machine.
      :agent-network/infrastructure [tools/infrastructure-step :agent-network/ssh-config]
      :agent-network/ssh-config [tools/ansible-local-step :agent-network/dns]
      ;; DNS before convergence: Traefik asks Let's Encrypt for a certificate
      ;; the moment it starts, and TLS-ALPN-01 only succeeds once the names
      ;; resolve to this host — the wildcard included, because the reverse
      ;; proxy issues its own certificates for generated endpoint hostnames
      ;; the same way. The record existing is necessary but not sufficient —
      ;; the playbook additionally waits for public resolvers to carry it
      ;; before starting anything.
      :agent-network/dns [tools/dns-step :agent-network/ansible]
      :agent-network/ansible [tools/ansible-step :agent-network/acceptance]
      :agent-network/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting
  [:agent-network/infrastructure :agent-network/dns :agent-network/ssh-config
   :agent-network/ansible :agent-network/acceptance :agent-network/ssh-cleanup])

(def workflow
  (-> (wf/workflow {:start :agent-network/start :wire-fn wire-fn})
      (wf/advice-add :agent-network/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :agent-network/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
