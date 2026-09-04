(ns io.github.getcolors.agent-network.ssh-test
  "Conformance tests for the SSH Keypair Standard.

  These cover the paths a real create can otherwise only reach by breaking a
  live deployment: an existing key with no state, state with no key, half a
  keypair, and a provider-side key this deployment does not own."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.agent-network.ssh :as ssh]
            [io.github.getcolors.agent-network.validate-test :refer [fixture optout do-fixture do-optout]]))

(defn- with-home
  "Run `f` with `~/.ssh` redirected into a fresh temporary home."
  [f]
  (let [home (str (fs/create-temp-dir {:prefix "agent-network-ssh"}))]
    (try
      (with-redefs [once-ssh/home-dir (constantly home)] (f home))
      (finally (fs/delete-tree home)))))

(defn- write! [path content]
  (io/make-parents path)
  (spit path content))

;;; ------------------------------------------------------------------ mode

(deftest build-renders-a-stable-placeholder-path
  ;; Goldens are committed, so a build must not name the operator's home.
  (with-home
    (fn [_]
      (let [opts (ssh/with-machine-key (assoc (fixture) :green/event :build))]
        (is (str/starts-with? (:ssh-public-key-path opts) ssh/build-placeholder-dir))
        (is (= (:ssh-public-key-path opts) (:vultr-ssh-keys opts)))
        (is (not (str/includes? (:ssh-private-key-path opts) (System/getProperty "user.home"))))))))

(deftest build-placeholder-lands-on-the-selected-providers-key
  ;; ONCE's table decides which desired-state key carries the machine key, so
  ;; a second provider needs no second branch here.
  (with-home
    (fn [_]
      (let [opts (ssh/with-machine-key (assoc (do-fixture) :green/event :build))]
        (is (= (:ssh-public-key-path opts) (:digitalocean-ssh-keys opts)))
        (is (not (contains? opts :vultr-ssh-keys)))
        (is (str/starts-with? (:ssh-public-key-path opts) ssh/build-placeholder-dir)))
      (let [opts (ssh/with-machine-key (assoc (do-optout) :green/event :build))]
        (is (= "00000000" (:digitalocean-ssh-keys opts)))
        (is (nil? (:ssh-public-key-path opts)))))))

(deftest real-events-render-the-real-path
  (with-home
    (fn [home]
      (let [opts (ssh/with-machine-key (assoc (fixture) :green/event :create))]
        (is (= (str (io/file home ".ssh" "agent-network-fixture")) (:ssh-private-key-path opts)))
        (is (= (str (io/file home ".ssh" "agent-network-fixture.pub")) (:ssh-public-key-path opts)))))))

(deftest opt-out-passes-through-untouched
  (with-home
    (fn [_]
      (doseq [event [:build :create :delete]]
        (let [opts (ssh/with-machine-key (assoc (optout) :green/event event))]
          (is (= "00000000-0000-0000-0000-000000000000" (:vultr-ssh-keys opts)))
          (is (nil? (:ssh-public-key-path opts)) (str event))
          (is (nil? (:ssh-keygen opts)) (str event)))))))

;;; -------------------------------------------------------- the create matrix

(deftest first-create-generates-the-keypair
  (with-home
    (fn [home]
      (let [opts (ssh/ensure-key! (assoc (fixture) :green/event :create) (constantly nil))
            prv (io/file home ".ssh" "agent-network-fixture")
            pub (io/file home ".ssh" "agent-network-fixture.pub")]
        (is (not (contains? opts :green/err)) (:green/err opts))
        (is (.exists prv))
        (is (.exists pub))
        (testing "ed25519, no passphrase, profile-named comment"
          (is (str/includes? (slurp pub) "ssh-ed25519"))
          (is (str/includes? (slurp pub) "agent-network-fixture managed by Colors")))
        (testing "600 on the private key, 700 on ~/.ssh"
          (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions prv))))
          (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions (io/file home ".ssh"))))))))))

(deftest converge-reuses-an-existing-key
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture")) "private")
      (write! (str (io/file home ".ssh" "agent-network-fixture.pub")) "ssh-ed25519 AAAA test")
      (let [opts (ssh/ensure-key! (assoc (fixture) :green/event :create)
                                  (constantly {:ip "192.0.2.10"}))]
        (is (not (contains? opts :green/err)))
        (is (= "private" (slurp (io/file home ".ssh" "agent-network-fixture")))
            "an existing key is reused, never regenerated")))))

(deftest state-without-a-key-is-an-error
  (with-home
    (fn [_]
      (let [opts (ssh/ensure-key! (assoc (fixture) :green/event :create)
                                  (constantly {:ip "192.0.2.10"}))]
        (is (= 1 (:green/exit opts)))
        (is (str/includes? (:green/err opts) "does not hold the machine key"))
        (is (str/includes? (:green/err opts) "rebuild"))))))

(deftest a-key-without-state-is-never-overwritten
  (with-home
    (fn [home]
      (let [prv (str (io/file home ".ssh" "agent-network-fixture"))]
        (write! prv "irreplaceable")
        (write! (str prv ".pub") "ssh-ed25519 AAAA test")
        (let [opts (ssh/ensure-key! (assoc (fixture) :green/event :create) (constantly nil))]
          (is (= 1 (:green/exit opts)))
          (is (str/includes? (:green/err opts) "no compute state is readable"))
          (is (str/includes? (:green/err opts) "survives")
              "the message must make the human the authorization boundary")
          (is (= "irreplaceable" (slurp prv)) "the key on disk is left alone"))))))

(deftest half-a-keypair-is-an-error
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture")) "private")
      (let [opts (ssh/ensure-key! (assoc (fixture) :green/event :create) (constantly nil))]
        (is (= 1 (:green/exit opts)))
        (is (str/includes? (:green/err opts) "half a keypair"))))))

(deftest opt-out-generates-nothing
  (with-home
    (fn [home]
      (let [opts (ssh/ensure-key! (assoc (optout) :green/event :create) (constantly nil))]
        (is (not (contains? opts :green/err)))
        (is (empty? (fs/list-dir home)) "opt-out mode must not touch ~/.ssh")))))

;;; ------------------------------------------------------------- preflight

(defn- preflight [opts account-keys]
  (with-redefs [once-ssh/fetch-account-keys (fn [_ _] account-keys)]
    (ssh/preflight! opts)))

(deftest preflight-passes-when-no-account-key-matches
  (with-home
    (fn [_]
      (let [opts (preflight (ssh/with-machine-key (assoc (fixture) :green/event :create))
                            [{:id "1" :name "someone-else" :public "ssh-ed25519 BBBB"}])]
        (is (not (contains? opts :green/err)))))))

(deftest preflight-passes-when-the-account-key-is-ours
  (with-home
    (fn [_]
      (let [opts (-> (assoc (fixture) :green/event :create
                            :once/ssh-state-params {:ssh_key_id "abc"})
                     ssh/with-machine-key
                     (preflight [{:id "abc" :name "agent-network-fixture" :public "ssh-ed25519 AAAA"}]))]
        (is (not (contains? opts :green/err)))))))

(deftest preflight-refuses-our-leftover-key
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture.pub")) "ssh-ed25519 AAAA comment")
      (let [opts (-> (assoc (fixture) :green/event :create)
                     ssh/with-machine-key
                     (preflight [{:id "abc" :name "agent-network-fixture" :public "ssh-ed25519 AAAA"}]))]
        (is (= 1 (:green/exit opts)))
        (is (str/includes? (:green/err opts) "previous delete"))
        (is (str/includes? (:green/err opts) "delete that key"))))))

(deftest preflight-refuses-a-foreign-key-and-says-do-not-delete-it
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture.pub")) "ssh-ed25519 OURS comment")
      (let [opts (-> (assoc (fixture) :green/event :create)
                     ssh/with-machine-key
                     (preflight [{:id "abc" :name "agent-network-fixture" :public "ssh-ed25519 THEIRS"}]))]
        (is (= 1 (:green/exit opts)))
        (is (str/includes? (:green/err opts) "Do not delete it"))))))

(deftest preflight-lists-keys-with-the-selected-providers-token
  ;; ONCE selects the REST API and the token by provider; this proves the
  ;; delegation hands each provider its own credential.
  (with-home
    (fn [_]
      (let [seen (atom [])]
        (with-redefs [once-ssh/fetch-account-keys (fn [provider token] (swap! seen conj [provider token]) [])]
          (ssh/preflight! (ssh/with-machine-key (assoc (do-fixture) :green/event :create
                                                       :do-token "do-secret" :vultr-api-key "wrong")))
          (ssh/preflight! (ssh/with-machine-key (assoc (fixture) :green/event :create
                                                       :vultr-api-key "vultr-secret" :do-token "wrong"))))
        (is (= [["digitalocean" "do-secret"] ["vultr" "vultr-secret"]] @seen))))))

(deftest preflight-failure-is-an-error-not-a-skip
  (with-home
    (fn [_]
      (with-redefs [once-ssh/fetch-account-keys (fn [_ _] (throw (ex-info "HTTP 500" {})))]
        (let [opts (ssh/preflight! (ssh/with-machine-key (assoc (fixture) :green/event :create)))]
          (is (= 1 (:green/exit opts)))
          (is (str/includes? (:green/err opts) "cannot list")))))))

;;; --------------------------------------------------------------- cleanup

(deftest delete-removes-the-keypair
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture")) "private")
      (write! (str (io/file home ".ssh" "agent-network-fixture.pub")) "public")
      (ssh/cleanup-step (assoc (fixture) :green/event :delete :ssh-keygen true))
      (is (not (.exists (io/file home ".ssh" "agent-network-fixture"))))
      (is (not (.exists (io/file home ".ssh" "agent-network-fixture.pub"))))
      (is (.exists (io/file home ".ssh")) "~/.ssh itself is the operator's, never removed"))))

(deftest cleanup-is-inert-on-create-and-in-opt-out-mode
  (with-home
    (fn [home]
      (write! (str (io/file home ".ssh" "agent-network-fixture")) "private")
      (ssh/cleanup-step (assoc (fixture) :green/event :create :ssh-keygen true))
      (is (.exists (io/file home ".ssh" "agent-network-fixture")))
      (ssh/cleanup-step (assoc (optout) :green/event :delete))
      (is (.exists (io/file home ".ssh" "agent-network-fixture"))))))
