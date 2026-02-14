# SPDX-License-Identifier: Unlicense

{ lib, newScope }:
lib.makeScope newScope (
  scope:
  let
    designTarget = "SPI";
  in
  {
    dependencies = scope.callPackage ../dependencies { };

    # RTL
    spi-compiled = scope.callPackage ./spi.nix { target = designTarget; };
    elaborate = scope.callPackage ./elaborate.nix {
      elaborator = scope.spi-compiled.elaborator;
    };
    mlirbc = scope.callPackage ./mlirbc.nix { };
    rtl = scope.callPackage ./rtl.nix { };
  }
)
