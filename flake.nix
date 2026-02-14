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
            iverilog   # Icarus Verilog (iverilog, vvp)
            verilator  # Verilator (verilator)
            python3    # Verilator 构建依赖
            clang-tools
            gcc
            bear
            gtkwave    # 波形查看器
            gnumake    # make
          ];

          shellHook = ''
            echo "🔧 SPI Master 仿真环境已就绪"
            echo "   iverilog $(iverilog -V 2>&1 | head -1)"
            echo ""
            echo "   make sim_master    - 仿真 SPI_Master (nandland, iverilog)"
            echo "   make sim_cs        - 仿真 SPI_Master_With_Single_CS (nandland, iverilog)"
            echo "   make sim_opencores - 仿真 OpenCores SPI Master (verilator)"
            echo "   make wave_master   - 打开波形 (SPI_Master)"
            echo "   make wave_cs       - 打开波形 (SPI_Master_With_Single_CS)"
            echo "   make wave_opencores- 打开波形 (OpenCores SPI)"
            echo "   make clean         - 清理构建产物"
          '';
        };
      });
}
