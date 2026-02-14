# SPI Master - Simulation Makefile
# Usage:
#   make sim_master     - [iverilog] 仿真 nandland SPI_Master
#   make sim_cs         - [iverilog] 仿真 nandland SPI_Master_With_Single_CS
#   make sim_opencores  - [verilator] 仿真 OpenCores SPI Master (回环测试)
#   make all            - 仿真全部
#   make wave_master    - 仿真并用 gtkwave 打开波形 (SPI_Master)
#   make wave_cs        - 仿真并用 gtkwave 打开波形 (SPI_Master_With_Single_CS)
#   make wave_opencores - 仿真并用 gtkwave 打开波形 (OpenCores SPI)
#   make clean          - 清理生成文件

# ─── 工具 ──────────────────────────────────────────────
IVERILOG  := iverilog
VVP       := vvp
VERILATOR := verilator
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

# ─── 默认目标 ──────────────────────────────────────────
.PHONY: all sim_master sim_cs sim_opencores \
        wave_master wave_cs wave_opencores clean

all: sim_master sim_cs sim_opencores

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
	$(VERILATOR) --cc --exe --build --trace --no-timing \
		--top-module spi_top \
		-I$(OC_DIR) --Mdir $(OC_VDIR) \
		-Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-INITIALDLY \
		$(OC_SOURCES) $(OC_TB_CPP) \
		-o Vspi_top

$(OC_VCD): $(OC_EXE) | $(BUILD_DIR)
	$(OC_EXE)

sim_opencores: $(OC_VCD)
	@echo "✓ OpenCores SPI 仿真完成，波形文件: $(OC_VCD)"

wave_opencores: $(OC_VCD)
	$(GTKWAVE) $(OC_VCD) &

# ═══════════════════════════════════════════════════════
#  辅助
# ═══════════════════════════════════════════════════════

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

clean:
	rm -rf $(BUILD_DIR)
