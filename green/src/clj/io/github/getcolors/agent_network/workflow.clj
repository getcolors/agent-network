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

(def defaults {:provider-compute validate/default-compute-provider
               :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "Compute params recorded in the infrastructure state; nil when the state
  holds none. An unreadable backend throws — `read-state` is where the two are
  told apart, because create and delete treat them differently."
  [opts]
  (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                        (tools/backend-credential-env opts))
          :params walk/keywordize-keys))

(defn read-state
  "One read of the compute state per run, shaped so a caller can tell nothing
  recorded from nothing readable: `{:params m}` where `m` may be nil, or
  `{:error message}`. Needs backend credentials only."
  [opts]
  (try {:params (state-output opts)}
       (catch Exception e {:error (ex-message e)})))

(defn lifecycle-event?
  "A real create or delete: the two events that touch a provider."
  [{:keys [event real?]}]
  (and real? (contains? #{:create :delete} event)))

(defn provider-validator
  "Compute Provider Standard §4 before the credentials. The recorded provider
  is compared with the selected one first, so a mistaken provider edit reports
  the actionable error — put it back and delete — rather than a missing token
  for the provider that was just selected. On a create an unreadable backend
  counts as no state (a fresh clone has none) and the credentials are checked
  as usual; on a delete `adopt-state` refuses it after validation."
  [opts event {:keys [params]}]
  (let [mismatch (validate/provider-state-errors opts params)]
    (if (seq mismatch) mismatch (validate/secret-errors opts event))))

(defn adopt-state
  "A real delete runs the ansible cleanup before the infrastructure step, so
  the instance address must come out of the existing state here. A readable
  state without compute params leaves :ip unset and the cleanup step skips
  itself; an unreadable backend fails loudly — swallowing it is how a live
  teardown ends up converging against 192.0.2.10."
  [opts {:keys [params error]}]
  (if error
    (assoc opts :green/exit 1
           :green/err (str "could not read the infrastructure state for the "
                           "delete cleanup: " error "\n"
                           "fix the backend credentials and retry; a delete that "
                           "cannot see its state has nothing to address"))
    (merge (ssh/with-machine-key opts) params {:green/exit 0})))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   ;; The state is read once, up front, on the same defaulted and overlaid
   ;; opts the validators see — the overlay is what carries the backend
   ;; credentials — and only for the two events that touch a provider. The
   ;; validator and the after-validate share the one read.
   (let [overlaid (green-cli/read-pars (merge defaults opts) env)
         context {:event (:green/event overlaid) :real? (lifecycle/real-run? overlaid)}
         state (when (lifecycle-event? context) (read-state overlaid))]
     (lifecycle/preflight
      opts {:defaults defaults :overlay green-cli/read-pars
            :validators
            [(fn [_ env _] (validate/env-errors env))
             (fn [opts _ _] (validate/state-errors opts))
             (fn [opts _ {:keys [event] :as ctx}]
               (when (lifecycle-event? ctx) (provider-validator opts event state)))
             (fn [opts _ {:keys [event real?]}]
               (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                 [(str "compute destruction is protected; set "
                       (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
            :after-validate
            ;; The machine key's create matrix and the provider preflight run
            ;; before any template is rendered: an unowned key on disk or at
            ;; the provider stops the run while stopping is still free. Delete
            ;; fills the same template values — a destroy renders before it
            ;; destroys — and adopts the recorded address, but checks no key,
            ;; because its key cleanup runs after the compute destroy.
            (fn [opts _ {:keys [event real?]}]
              (cond
                (and real? (= :delete event))
                (adopt-state opts state)

                (and real? (= :create event))
                (let [opts (ssh/ensure-key! opts (fn [_] (:params state)))]
                  (if (wf/failed? opts)
                    opts
                    (let [opts (ssh/preflight! (ssh/with-machine-key opts))
                          opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
                      (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

                :else
                (assoc (ssh/with-machine-key opts) :green/exit 0)))} env))))

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
