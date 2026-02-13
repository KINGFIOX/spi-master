# SPI Master - Icarus Verilog Simulation Makefile
# Usage:
#   make sim_master     - 编译并仿真 SPI_Master
#   make sim_cs         - 编译并仿真 SPI_Master_With_Single_CS
#   make all            - 仿真全部
#   make wave_master    - 仿真 SPI_Master 并用 gtkwave 打开波形
#   make wave_cs        - 仿真 SPI_Master_With_Single_CS 并用 gtkwave 打开波形
#   make clean          - 清理生成文件

# ─── 工具 ──────────────────────────────────────────────
IVERILOG  := iverilog
VVP       := vvp
GTKWAVE   := gtkwave
IVFLAGS   := -g2012

# ─── 目录 ──────────────────────────────────────────────
SRC_DIR   := Verilog/source
SIM_DIR   := Verilog/sim
BUILD_DIR := build

# ─── 源文件 ────────────────────────────────────────────
SPI_MASTER_SRC  := $(SRC_DIR)/SPI_Master.v
SPI_CS_SRC      := $(SRC_DIR)/SPI_Master_With_Single_CS.v

SPI_MASTER_TB   := $(SIM_DIR)/SPI_Master_TB.sv
SPI_CS_TB       := $(SIM_DIR)/SPI_Master_With_Single_CS_TB.sv

# ─── 输出 ──────────────────────────────────────────────
MASTER_VVP := $(BUILD_DIR)/spi_master_tb.vvp
MASTER_VCD := $(BUILD_DIR)/spi_master.vcd

CS_VVP     := $(BUILD_DIR)/spi_master_cs_tb.vvp
CS_VCD     := $(BUILD_DIR)/spi_master_cs.vcd

# ─── 默认目标 ──────────────────────────────────────────
.PHONY: all sim_master sim_cs wave_master wave_cs clean

all: sim_master sim_cs

# ─── SPI_Master 仿真 ──────────────────────────────────
$(MASTER_VVP): $(SPI_MASTER_SRC) $(SPI_MASTER_TB) | $(BUILD_DIR)
	$(IVERILOG) $(IVFLAGS) -o $@ $(SPI_MASTER_SRC) $(SPI_MASTER_TB)

$(MASTER_VCD): $(MASTER_VVP)
	cd $(BUILD_DIR) && $(VVP) ../$(MASTER_VVP)
	@mv dump.vcd $(MASTER_VCD) 2>/dev/null || mv $(BUILD_DIR)/dump.vcd $(MASTER_VCD) 2>/dev/null || true

sim_master: $(MASTER_VCD)
	@echo "✓ SPI_Master 仿真完成，波形文件: $(MASTER_VCD)"

wave_master: $(MASTER_VCD)
	$(GTKWAVE) $(MASTER_VCD) &

# ─── SPI_Master_With_Single_CS 仿真 ───────────────────
$(CS_VVP): $(SPI_CS_SRC) $(SPI_MASTER_SRC) $(SPI_CS_TB) | $(BUILD_DIR)
	$(IVERILOG) $(IVFLAGS) -I $(SRC_DIR) -o $@ $(SPI_CS_SRC) $(SPI_CS_TB)

$(CS_VCD): $(CS_VVP)
	cd $(BUILD_DIR) && $(VVP) ../$(CS_VVP)
	@mv dump.vcd $(CS_VCD) 2>/dev/null || mv $(BUILD_DIR)/dump.vcd $(CS_VCD) 2>/dev/null || true

sim_cs: $(CS_VCD)
	@echo "✓ SPI_Master_With_Single_CS 仿真完成，波形文件: $(CS_VCD)"

wave_cs: $(CS_VCD)
	$(GTKWAVE) $(CS_VCD) &

# ─── 辅助 ─────────────────────────────────────────────
$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

clean:
	rm -rf $(BUILD_DIR)
