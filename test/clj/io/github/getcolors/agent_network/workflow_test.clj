(ns io.github.getcolors.agent-network.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.agent-network.validate-test :refer [fixture]]
            [io.github.getcolors.agent-network.workflow :as workflow]))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest build-and-dry-run-never-touch-ssh
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone.
  (doseq [opts [(assoc (fixture) :green/event :build)
                (assoc (fixture) :green/event :create :green/dry-run true)]]
    (let [result (workflow/start-step opts {})]
      (is (= 0 (:green/exit result)))
      (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
          "a build must not name the operator's home directory"))))

(deftest real-create-requires-credentials
  (let [r (workflow/start-step (assoc (fixture) :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (str/includes? (:green/err r) "COLORS_PAR_CLOUDFLARE_API_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_ANTHROPIC_API_KEY"))))

(deftest delete-is-protected
  (let [r (workflow/start-step (assoc (fixture) :green/event :delete) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

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
