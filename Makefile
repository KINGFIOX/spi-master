# SPI Master - Simulation Makefile
# Usage:
#   make sim_master     - [iverilog] 仿真 nandland SPI_Master
#   make sim_cs         - [iverilog] 仿真 nandland SPI_Master_With_Single_CS
#   make sim_opencores  - [verilator] 仿真 OpenCores SPI Master (回环测试)
#   make sim_chisel     - [verilator] 仿真 Chisel SPI Master (回环测试)
#   make all            - 仿真全部
#   make wave_master    - 仿真并用 gtkwave 打开波形 (SPI_Master)
#   make wave_cs        - 仿真并用 gtkwave 打开波形 (SPI_Master_With_Single_CS)
#   make wave_opencores - 仿真并用 gtkwave 打开波形 (OpenCores SPI)
#   make wave_chisel    - 仿真并用 gtkwave 打开波形 (Chisel SPI)
#   make clean          - 清理生成文件

# ─── 工具 ──────────────────────────────────────────────
IVERILOG  := iverilog
VVP       := vvp
VERILATOR := verilator
MILL      := mill
FIRTOOL   := firtool
GTKWAVE   := gtkwave
IVFLAGS   := -g2012

# ─── 目录 ──────────────────────────────────────────────
SRC_DIR   := nandland/source
SIM_DIR   := nandland/sim
OC_DIR    := opencores
OC_SIM    := opencores/sim
BUILD_DIR := build

# ─── nandland 源文件 ─────────────────────────────────────
SPI_MASTER_SRC  := $(SRC_DIR)/SPI_Master.v
SPI_CS_SRC      := $(SRC_DIR)/SPI_Master_With_Single_CS.v

SPI_MASTER_TB   := $(SIM_DIR)/SPI_Master_TB.sv
SPI_CS_TB       := $(SIM_DIR)/SPI_Master_With_Single_CS_TB.sv

# ─── OpenCores 源文件 ───────────────────────────────────
OC_SOURCES := $(OC_DIR)/spi_top.v $(OC_DIR)/spi_clgen.v $(OC_DIR)/spi_shift.v
OC_TB_CPP  := $(OC_SIM)/sim_spi_top.cpp

# ─── 输出文件 ────────────────────────────────────────────
MASTER_VVP := $(BUILD_DIR)/spi_master_tb.vvp
MASTER_VCD := $(BUILD_DIR)/spi_master.vcd

CS_VVP     := $(BUILD_DIR)/spi_master_cs_tb.vvp
CS_VCD     := $(BUILD_DIR)/spi_master_cs.vcd

OC_VDIR    := $(BUILD_DIR)/verilator_opencores
OC_EXE     := $(OC_VDIR)/Vspi_top
OC_VCD     := $(BUILD_DIR)/opencores_spi.vcd

# ─── Chisel SPI 文件 ──────────────────────────────────
CH_ELABORATE := $(BUILD_DIR)/chisel_spi
CH_RTL       := $(BUILD_DIR)/chisel_rtl
CH_TB_CPP    := $(OC_SIM)/sim_chisel_spi.cpp
CH_VDIR      := $(BUILD_DIR)/verilator_chisel
CH_EXE       := $(CH_VDIR)/VSPI
CH_VCD       := $(BUILD_DIR)/chisel_spi.vcd

# ─── 默认目标 ──────────────────────────────────────────
.PHONY: all sim_master sim_cs sim_opencores sim_chisel \
        wave_master wave_cs wave_opencores wave_chisel \
        elaborate_chisel rtl_chisel clean

all: sim_master sim_cs sim_opencores sim_chisel

# ═══════════════════════════════════════════════════════
#  nandland SPI_Master 仿真 (Icarus Verilog)
# ═══════════════════════════════════════════════════════

$(MASTER_VVP): $(SPI_MASTER_SRC) $(SPI_MASTER_TB) | $(BUILD_DIR)
	$(IVERILOG) $(IVFLAGS) -o $@ $(SPI_MASTER_SRC) $(SPI_MASTER_TB)

$(MASTER_VCD): $(MASTER_VVP)
	cd $(BUILD_DIR) && $(VVP) ../$(MASTER_VVP)
	@mv dump.vcd $(MASTER_VCD) 2>/dev/null || mv $(BUILD_DIR)/dump.vcd $(MASTER_VCD) 2>/dev/null || true

sim_master: $(MASTER_VCD)
	@echo "✓ SPI_Master 仿真完成，波形文件: $(MASTER_VCD)"

wave_master: $(MASTER_VCD)
	$(GTKWAVE) $(MASTER_VCD) &

# ═══════════════════════════════════════════════════════
#  nandland SPI_Master_With_Single_CS 仿真 (Icarus Verilog)
# ═══════════════════════════════════════════════════════

$(CS_VVP): $(SPI_CS_SRC) $(SPI_MASTER_SRC) $(SPI_CS_TB) | $(BUILD_DIR)
	$(IVERILOG) $(IVFLAGS) -I $(SRC_DIR) -o $@ $(SPI_CS_SRC) $(SPI_CS_TB)

$(CS_VCD): $(CS_VVP)
	cd $(BUILD_DIR) && $(VVP) ../$(CS_VVP)
	@mv dump.vcd $(CS_VCD) 2>/dev/null || mv $(BUILD_DIR)/dump.vcd $(CS_VCD) 2>/dev/null || true

sim_cs: $(CS_VCD)
	@echo "✓ SPI_Master_With_Single_CS 仿真完成，波形文件: $(CS_VCD)"

wave_cs: $(CS_VCD)
	$(GTKWAVE) $(CS_VCD) &

# ═══════════════════════════════════════════════════════
#  OpenCores SPI Master 仿真 (Verilator)
# ═══════════════════════════════════════════════════════

$(OC_EXE): $(OC_SOURCES) $(OC_DIR)/spi_defines.v $(OC_TB_CPP) | $(BUILD_DIR)
	$(VERILATOR) --cc --exe --build --trace \
		--top-module spi_top \
		-I$(OC_DIR) --Mdir $(OC_VDIR) \
		-Wno-WIDTH -Wno-CASEINCOMPLETE \
		$(OC_SOURCES) $(OC_TB_CPP) \
		-o Vspi_top

$(OC_VCD): $(OC_EXE) | $(BUILD_DIR)
	$(OC_EXE)

sim_opencores: $(OC_VCD)
	@echo "✓ OpenCores SPI 仿真完成，波形文件: $(OC_VCD)"

wave_opencores: $(OC_VCD)
	$(GTKWAVE) $(OC_VCD) &

# ═══════════════════════════════════════════════════════
#  Chisel SPI Master 仿真 (Mill + firtool + Verilator)
# ═══════════════════════════════════════════════════════

# Step 1: Elaborate Chisel → FIRRTL
$(CH_ELABORATE)/SPI.fir: spi/src/SPI.scala elaborator/src/SPI.scala configs/SPI.json | $(BUILD_DIR)
	@mkdir -p $(CH_ELABORATE)
	$(MILL) -i elaborator.runMain org.chipsalliance.spi.elaborator.SPIMain \
		design --parameter configs/SPI.json --target-dir $(CH_ELABORATE)

elaborate_chisel: $(CH_ELABORATE)/SPI.fir

# Step 2: FIRRTL → SystemVerilog
$(CH_RTL)/SPI.sv: $(CH_ELABORATE)/SPI.fir
	@mkdir -p $(CH_RTL)
	$(FIRTOOL) $(CH_ELABORATE)/SPI.fir \
		--annotation-file $(CH_ELABORATE)/SPI.anno.json \
		-O=release --split-verilog \
		--preserve-values=all \
		--lowering-options=verifLabels,omitVersionComment \
		--strip-debug-info \
		--disable-all-randomization \
		-o $(CH_RTL)

rtl_chisel: $(CH_RTL)/SPI.sv

# Step 3: Verilator compile
$(CH_EXE): $(CH_RTL)/SPI.sv $(CH_TB_CPP)
	$(VERILATOR) --cc --exe --build --trace \
		--top-module SPI \
		--Mdir $(CH_VDIR) \
		-Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-UNUSEDSIGNAL \
		$(CH_RTL)/SPI.sv $(CH_RTL)/SPIClgen.sv $(CH_RTL)/SPIShift.sv \
		$(CH_TB_CPP) \
		-o VSPI

# Step 4: Run simulation
$(CH_VCD): $(CH_EXE) | $(BUILD_DIR)
	$(CH_EXE)

sim_chisel: $(CH_VCD)
	@echo "✓ Chisel SPI 仿真完成，波形文件: $(CH_VCD)"

wave_chisel: $(CH_VCD)
	$(GTKWAVE) $(CH_VCD) &

# ═══════════════════════════════════════════════════════
#  辅助
# ═══════════════════════════════════════════════════════

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

clean:
	rm -rf $(BUILD_DIR)
