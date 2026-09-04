(ns io.github.getcolors.agent-network.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.agent-network.validate-test :refer [fixture do-fixture]]
            [io.github.getcolors.agent-network.workflow :as workflow]))

;; The compute state is read once per run, through `state-output`, on a real
;; create or delete. Every lifecycle test stubs it: nil is a readable state
;; holding no compute, a map is a recorded `params`, and a throw is a backend
;; that cannot be read.
(defn- start [opts state]
  (with-redefs [workflow/state-output (fn [_] state)]
    (workflow/start-step opts {})))

(defn- start-unreadable [opts]
  ;; The shape `green.tofu/outputs` throws: an ex-info carrying `:dir`. Only
  ;; that is an unreadable backend; anything else propagates as a defect.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "tofu output failed: no backend" {:dir "x"})))]
    (workflow/start-step opts {})))

(def credentials
  {:vultr-api-key "v" :do-token "d" :cloudflare-api-token "c"
   :r2-access-key-id "a" :r2-secret-access-key "s" :anthropic-api-key "k"})

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {}))))
  (is (= 0 (:green/exit (workflow/start-step (assoc (do-fixture) :green/event :build) {})))))

(deftest build-and-dry-run-never-touch-ssh-or-state
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone. Nor do they
  ;; read the backend: a throwing state read proves nothing on these paths
  ;; reaches it.
  (doseq [opts [(assoc (fixture) :green/event :build)
                (assoc (fixture) :green/event :create :green/dry-run true)
                (assoc (do-fixture) :green/event :delete :green/dry-run true)]]
    (let [result (start-unreadable opts)]
      (is (= 0 (:green/exit result)))
      (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
          "a build must not name the operator's home directory"))))

(deftest real-create-requires-credentials
  (let [r (start (assoc (fixture) :green/event :create) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? (:green/err r) "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_ANTHROPIC_API_KEY"))))

(deftest real-create-and-delete-require-the-selected-providers-credentials
  (testing "create on DigitalOcean"
    (let [r (start (assoc (do-fixture) :green/event :create) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
      (is (str/includes? (:green/err r) "COLORS_PAR_ANTHROPIC_API_KEY"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY")))))
  (testing "delete on DigitalOcean asks for no Anthropic key"
    (let [r (start (assoc (do-fixture) :green/event :delete :compute-prevent-destroy false) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
      (is (not (str/includes? (:green/err r) "ANTHROPIC")))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY")))))
  (testing "delete on Vultr"
    (let [r (start (assoc (fixture) :green/event :delete :compute-prevent-destroy false) nil)]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))))

(deftest delete-is-protected
  (let [r (start (assoc (fixture) :green/event :delete) nil)]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

;; --- provider switching is a rebuild, never an apply -----------------------

(deftest a-provider-switch-is-refused-on-create-and-delete
  (doseq [event [:create :delete]]
    (testing (str "Vultr selected, DigitalOcean recorded, on " (name event))
      (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                     {:provider "digitalocean" :ip "203.0.113.9"})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (:green/err r)
                           "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"))
        ;; The validator order is the thing under test: the actionable error,
        ;; not a missing token for the provider that was just selected.
        (is (not (str/includes? (:green/err r) "required credential is not set")))))
    (testing (str "DigitalOcean selected, Vultr recorded, on " (name event))
      (let [r (start (assoc (do-fixture) :green/event event :compute-prevent-destroy false)
                     {:provider "vultr" :ip "203.0.113.9"})]
        (is (= 2 (:green/exit r)))
        (is (str/includes? (:green/err r) "state holds a vultr machine; set provider-compute back to vultr"))
        (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN")))))))

(deftest legacy-state-accepts-only-the-default-provider
  (doseq [event [:create :delete]]
    (let [r (start (assoc (fixture) :green/event event :compute-prevent-destroy false)
                   {:ip "203.0.113.9"})]
      (is (not (str/includes? (:green/err r) "state holds")) (name event))
      (is (str/includes? (:green/err r) "required credential is not set") (name event)))
    (let [r (start (assoc (do-fixture) :green/event event :compute-prevent-destroy false)
                   {:ip "203.0.113.9"})]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "no recorded provider") (name event))
      (is (str/includes? (:green/err r) "set provider-compute back to vultr and delete first"))
      (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))))))

(deftest a-matching-provider-passes-to-the-credentials
  (let [r (start (assoc (fixture) :green/event :create) {:provider "vultr" :ip "203.0.113.9"})]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))))

(deftest an-unreadable-backend-counts-as-no-state-on-create
  ;; A fresh clone has no readable state and must still be able to create.
  (let [r (start-unreadable (assoc (fixture) :green/event :create))]
    (is (= 2 (:green/exit r)))
    (is (not (str/includes? (:green/err r) "could not read")))
    (is (not (str/includes? (:green/err r) "state holds")))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))))

(deftest an-unreadable-backend-fails-a-real-delete-closed
  ;; Swallowing it is how a teardown ends up converging against 192.0.2.10.
  (let [r (start-unreadable (merge (fixture) credentials
                                   {:green/event :delete :compute-prevent-destroy false}))]
    (is (= 1 (:green/exit r)))
    (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup"))
    (is (str/includes? (:green/err r) "no backend"))))

(deftest a-real-delete-adopts-the-recorded-address
  (let [r (start (merge (fixture) credentials {:green/event :delete :compute-prevent-destroy false})
                 {:provider "vultr" :ip "203.0.113.9" :user "root"})]
    (is (= 0 (:green/exit r)))
    (is (= "203.0.113.9" (:ip r))))
  ;; A readable state without compute leaves the address unset, and the
  ;; cleanup step skips itself.
  (let [r (start (merge (fixture) credentials {:green/event :delete :compute-prevent-destroy false})
                 nil)]
    (is (= 0 (:green/exit r)))
    (is (nil? (:ip r)))))

(deftest graph-orders-the-stack
  (is (= [:agent-network/infrastructure]
         (vec (rest (workflow/wire-fn :agent-network/start {:green/event :create})))))
  (is (= [:agent-network/ssh-config]
         (vec (rest (workflow/wire-fn :agent-network/infrastructure {:green/event :create})))))
  (is (= [:agent-network/dns]
         (vec (rest (workflow/wire-fn :agent-network/ssh-config {:green/event :create})))))
  ;; DNS before convergence: Traefik asks Let's Encrypt for a certificate as
  ;; soon as it starts, and TLS-ALPN-01 only succeeds once the names resolve —
  ;; the wildcard included, for the reverse proxy's endpoint certificates.
  (is (= [:agent-network/ansible]
         (vec (rest (workflow/wire-fn :agent-network/dns {:green/event :create})))))
  (is (= [:agent-network/acceptance]
         (vec (rest (workflow/wire-fn :agent-network/ansible {:green/event :create}))))))

(deftest delete-removes-the-key-after-the-compute-destroy
  ;; The ordering is what makes "key present ⇔ deployment exists" hold: a
  ;; failed destroy never reaches the cleanup step, and correctly leaves the
  ;; key that is still the only credential to whatever survived.
  (is (= [:agent-network/ansible]
         (vec (rest (workflow/wire-fn :agent-network/start {:green/event :delete})))))
  (is (= [:agent-network/ssh-cleanup]
         (vec (rest (workflow/wire-fn :agent-network/infrastructure {:green/event :delete})))))
  (is (empty? (rest (workflow/wire-fn :agent-network/ssh-cleanup {:green/event :delete})))))
