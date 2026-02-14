{
  description = "SPI Master - Verilog simulation environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            iverilog  # Icarus Verilog (iverilog, vvp)
            gtkwave   # 波形查看器
            gnumake   # make
          ];

          shellHook = ''
            echo "🔧 SPI Master 仿真环境已就绪"
            echo "   iverilog $(iverilog -V 2>&1 | head -1)"
            echo ""
            echo "   make sim_master   - 仿真 SPI_Master (nandland)"
            echo "   make sim_cs       - 仿真 SPI_Master_With_Single_CS (nandland)"
            echo "   make wave_master  - 仿真并打开波形 (SPI_Master)"
            echo "   make wave_cs      - 仿真并打开波形 (SPI_Master_With_Single_CS)"
            echo "   make clean        - 清理构建产物"
          '';
        };
      });
}
