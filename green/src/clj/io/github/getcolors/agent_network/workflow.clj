(ns io.github.getcolors.agent-network.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.agent-network.compute :as compute]
            [io.github.getcolors.agent-network.ssh-config :as ssh-config]
            [io.github.getcolors.agent-network.tools :as tools]
            [io.github.getcolors.agent-network.validate :as validate]))

(def defaults {:provider-compute "vultr" :provider-dns "cloudflare"
               :provider-backend "r2" :compute-prevent-destroy true
               :workdir ".colors"})

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
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (= :create event)) (ssh-config/preflight! (assoc opts :green/exit 0)) (assoc opts :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :agent-network/start [start-step :agent-network/load]
      :agent-network/load [compute/load-step :agent-network/ansible]
      :agent-network/ansible [tools/ansible-step :agent-network/dns]
      ;; The `~/.ssh/config` block goes before the destroy, the opposite of the
      ;; keypair below. A block that outlives its host is stale but harmless; a
      ;; key that predeceases its host locks the operator out of a machine that
      ;; still exists. Both orders are deliberate; see standards/ssh-config.md.
      :agent-network/dns [tools/dns-step :agent-network/ssh-config]
      :agent-network/ssh-config [tools/ansible-local-step :agent-network/infrastructure]
      :agent-network/infrastructure [tools/infrastructure-step])
    (case step
      :agent-network/start [start-step :agent-network/infrastructure]
      ;; After compute, which is where the address first exists, and before the
      ;; stage that converges the machine.
      :agent-network/infrastructure [tools/infrastructure-step :agent-network/ssh-config]
      :agent-network/ssh-config [tools/ansible-local-step :agent-network/dns]
      ;; DNS before convergence: Traefik asks Let's Encrypt for a certificate
      ;; the moment it starts, and TLS-ALPN-01 only succeeds once the names
      ;; resolve to this host. The record existing is necessary but not
      ;; sufficient — the playbook additionally waits for public resolvers to
      ;; carry it before starting anything.
      :agent-network/dns [tools/dns-step :agent-network/ansible]
      :agent-network/ansible [tools/ansible-step :agent-network/acceptance]
      :agent-network/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting
  [:agent-network/infrastructure :agent-network/dns :agent-network/ssh-config
   :agent-network/ansible :agent-network/acceptance :agent-network/load])

(def workflow
  (-> (wf/workflow {:start :agent-network/start :wire-fn wire-fn})
      (wf/advice-add :agent-network/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
