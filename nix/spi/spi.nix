# SPDX-License-Identifier: Unlicense

{
  lib,
  stdenv,
  makeWrapper,
  writeShellApplication,
  jdk21,
  git,

  # chisel deps
  mill,
  espresso,
  mlir-install,
  circt-install,
  jextract-21,
  add-determinism,

  dependencies,
  mill-ivy-fetcher,
  mill-ivy-env-shell-hook,
  ivy-gather,

  target,
}:

let
  spiMillDeps = ivy-gather ../dependencies/locks/spi-lock.nix;

  self = stdenv.mkDerivation rec {
    name = "spi";

    mainClass = "org.chipsalliance.spi.elaborator.${target}Main";

    src =
      with lib.fileset;
      toSource {
        root = ./../..;
        fileset = unions [
          ./../../build.mill
          ./../../common.mill
          ./../../spi
          ./../../elaborator
        ];
      };

    buildInputs = with dependencies; [
      ivy-chisel.setupHook
      spiMillDeps
    ];

    nativeBuildInputs = with dependencies; [
      makeWrapper

      mill
      circt-install
      jextract-21
      add-determinism
      espresso
      git
    ];

    passthru = {
      bump = writeShellApplication {
        name = "bump-spi-mill-lock";
        runtimeInputs = [
          mill
          mill-ivy-fetcher
        ];
        text = ''
          ivyLocal="${dependencies.ivyLocalRepo}"
          export JAVA_TOOL_OPTIONS="''${JAVA_TOOL_OPTIONS:-} -Dcoursier.ivy.home=$ivyLocal -Divy.home=$ivyLocal"

          mif run -p "${src}" -o ./nix/dependencies/locks/spi-lock.nix "$@"
        '';
      };
      inherit target;
      inherit env;
    };

    shellHook = ''
      ${mill-ivy-env-shell-hook}

      mill -i mill.bsp.BSP/install
    '';

    env = {
      CIRCT_INSTALL_PATH = circt-install;
      MLIR_INSTALL_PATH = mlir-install;
      JEXTRACT_INSTALL_PATH = jextract-21;
    };

    outputs = [
      "out"
      "elaborator"
    ];

    meta.mainProgram = "elaborator";

    buildPhase = ''
      mill -i '__.assembly'
    '';

    installPhase = ''
      mkdir -p $out/share/java

      add-determinism -j $NIX_BUILD_CORES out/elaborator/assembly.dest/out.jar

      mv out/elaborator/assembly.dest/out.jar $out/share/java/elaborator.jar

      mkdir -p $elaborator/bin
      makeWrapper ${jdk21}/bin/java $elaborator/bin/elaborator \
        --add-flags "--enable-preview -Djava.library.path=${mlir-install}/lib:${circt-install}/lib -cp $out/share/java/elaborator.jar ${mainClass}"
    '';
  };
in
self
