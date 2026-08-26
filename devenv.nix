{ pkgs, ... }:
{
  languages.clojure.enable = true;
  languages.opentofu.enable = true;
  packages = with pkgs; [
    ansible babashka curl jq openssh unzip
    openjdk21 netcat-openbsd
  ];
}
