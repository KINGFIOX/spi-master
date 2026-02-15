///////////////////////////////////////////////////////////////////////////////
// sim_chisel_spi.cpp
// Chisel SPI Master - Verilator simulation testbench
//
// Reuses the same test cases from sim_spi_top.cpp, but adapted for the
// Chisel-generated SPI module which has:
//   - Module name: SPI (instead of spi_top)
//   - clock / reset (active-HIGH, instead of pclk / presetn active-LOW)
//   - camelCase port names (intO, ssPadO, sclkPadO, mosiPadO, misoPadI)
//
// Test contents:
//   1.  8-bit SPI loopback
//   2. 16-bit SPI loopback
//   3. 32-bit SPI loopback
//   4. Register read/write verification
///////////////////////////////////////////////////////////////////////////////

#include "VSPI.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <cstdio>
#include <cstdlib>
#include <memory>

// ─── Register addresses (byte address, paddr[4:2] selects register) ─────
static constexpr uint8_t ADDR_TX0    = 0 << 2;  // 0x00
static constexpr uint8_t ADDR_TX1    = 1 << 2;  // 0x04
static constexpr uint8_t ADDR_CTRL   = 4 << 2;  // 0x10
static constexpr uint8_t ADDR_DIVIDE = 5 << 2;  // 0x14
static constexpr uint8_t ADDR_SS     = 6 << 2;  // 0x18

// ─── Control register bits ──────────────────────────────────────────────
static constexpr uint32_t CTRL_GO     = 1 << 8;
static constexpr uint32_t CTRL_RX_NEG = 1 << 9;
static constexpr uint32_t CTRL_TX_NEG = 1 << 10;
static constexpr uint32_t CTRL_LSB    = 1 << 11;
static constexpr uint32_t CTRL_IE     = 1 << 12;
static constexpr uint32_t CTRL_ASS    = 1 << 13;

// ─── Globals ────────────────────────────────────────────────────────────
static VSPI*           dut = nullptr;
static VerilatedVcdC*  tfp = nullptr;
static uint64_t        sim_time = 0;
static int             test_pass = 0;
static int             test_fail = 0;

// ─── Clock tick ─────────────────────────────────────────────────────────
// One full system clock cycle (falling edge → rising edge)
// MOSI is looped back to MISO
static void tick() {
    // Rising edge
    dut->clock = 1;
    dut->misoPadI = dut->mosiPadO;  // loopback
    dut->eval();
    if (tfp) tfp->dump(sim_time++);

    // Falling edge
    dut->clock = 0;
    dut->misoPadI = dut->mosiPadO;  // loopback
    dut->eval();
    if (tfp) tfp->dump(sim_time++);
}

// ─── Reset ──────────────────────────────────────────────────────────────
// Chisel SPI uses active-HIGH reset
static void do_reset() {
    dut->reset    = 1;   // active-high reset asserted
    dut->psel     = 0;
    dut->penable  = 0;
    dut->pwrite   = 0;
    dut->pstrb    = 0;
    dut->paddr    = 0;
    dut->pwdata   = 0;
    dut->misoPadI = 0;

    for (int i = 0; i < 10; i++) tick();

    dut->reset = 0;      // release reset
    tick();
}

// ─── APB write ──────────────────────────────────────────────────────────
static void apb_write(uint8_t addr, uint32_t data) {
    // SETUP phase: PSEL=1, PENABLE=0
    dut->paddr   = addr;
    dut->pwdata  = data;
    dut->pstrb   = 0xF;
    dut->pwrite  = 1;
    dut->psel    = 1;
    dut->penable = 0;
    tick();

    // ACCESS phase: PENABLE=1, wait for PREADY
    dut->penable = 1;
    do { tick(); } while (!dut->pready);

    // IDLE phase: PSEL=0, PENABLE=0
    dut->psel    = 0;
    dut->penable = 0;
    dut->pwrite  = 0;
    tick();
}

// ─── APB read ───────────────────────────────────────────────────────────
static uint32_t apb_read(uint8_t addr) {
    // SETUP phase: PSEL=1, PENABLE=0
    dut->paddr   = addr;
    dut->pwrite  = 0;
    dut->pstrb   = 0xF;
    dut->psel    = 1;
    dut->penable = 0;
    tick();

    // ACCESS phase: PENABLE=1, wait for PREADY
    dut->penable = 1;
    do { tick(); } while (!dut->pready);

    uint32_t data = dut->prdata;

    // IDLE phase: PSEL=0, PENABLE=0
    dut->psel    = 0;
    dut->penable = 0;
    tick();

    return data;
}

// ─── Result check ───────────────────────────────────────────────────────
static void check(const char* name, uint32_t expected, uint32_t actual,
                  uint32_t mask = 0xFFFFFFFF) {
    actual   &= mask;
    expected &= mask;
    if (actual == expected) {
        printf("  PASS %s: expected 0x%X, got 0x%X\n", name, expected, actual);
        test_pass++;
    } else {
        printf("  FAIL %s: expected 0x%X, got 0x%X\n", name, expected, actual);
        test_fail++;
    }
}

// ─── SPI transfer helper ────────────────────────────────────────────────
static uint32_t spi_transfer(uint32_t tx_data, uint32_t char_len,
                             uint32_t divider) {
    apb_write(ADDR_DIVIDE, divider);
    apb_write(ADDR_SS, 0x01);
    apb_write(ADDR_TX0, tx_data);

    uint32_t ctrl = (char_len & 0x7F) | CTRL_GO | CTRL_ASS | CTRL_TX_NEG;
    apb_write(ADDR_CTRL, ctrl);

    // pready blocks TX/RX reads during transfer — no polling needed.
    // apb_read will wait until the transfer finishes, then return RX data.
    return apb_read(ADDR_TX0);
}

// ═══════════════════════════════════════════════════════════════════════════
int main(int argc, char** argv) {
    VerilatedContext* contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    contextp->traceEverOn(true);

    dut = new VSPI{contextp};
    tfp = new VerilatedVcdC;
    dut->trace(tfp, 99);
    tfp->open("build/chisel_spi.vcd");

    printf("════════════════════════════════════════════════════\n");
    printf("  Chisel SPI Master (APB) - Verilator Simulation\n");
    printf("════════════════════════════════════════════════════\n\n");

    // ─── Reset ──────────────────────────────────────────
    do_reset();
    printf("[time %5lu] reset done\n\n", (unsigned long)sim_time);

    // ─── Warmup transfer ────────────────────────────────
    {
        printf("── Warmup: first transfer (post-reset) ──\n");
        uint32_t rx = spi_transfer(0xFF, 8, 4);
        printf("  TX = 0xFF, RX = 0x%02X (initial offset expected)\n\n",
               rx & 0xFF);
    }

    // ─── Test 1: 8-bit loopback ─────────────────────────
    {
        printf("── Test 1: 8-bit loopback (TX=0xA5, div=4) ──\n");
        uint32_t rx = spi_transfer(0xA5, 8, 4);
        printf("  TX = 0x%02X, RX = 0x%02X\n", 0xA5, rx & 0xFF);
        check("8-bit loopback", 0xA5, rx, 0xFF);
        printf("\n");
    }

    // ─── Test 2: 16-bit loopback ────────────────────────
    {
        printf("── Test 2: 16-bit loopback (TX=0xBEEF, div=4) ──\n");
        uint32_t rx = spi_transfer(0xBEEF, 16, 4);
        printf("  TX = 0x%04X, RX = 0x%04X\n", 0xBEEF, rx & 0xFFFF);
        check("16-bit loopback", 0xBEEF, rx, 0xFFFF);
        printf("\n");
    }

    // ─── Test 3: 32-bit loopback ────────────────────────
    {
        printf("── Test 3: 32-bit loopback (TX=0xDEADBEEF, div=4) ──\n");
        uint32_t rx = spi_transfer(0xDEADBEEF, 32, 4);
        printf("  TX = 0x%08X, RX = 0x%08X\n", (unsigned)0xDEADBEEF, rx);
        check("32-bit loopback", 0xDEADBEEF, rx, 0xFFFFFFFF);
        printf("\n");
    }

    // ─── Test 4: Register read/write ────────────────────
    {
        printf("── Test 4: Register read/write ──\n");

        apb_write(ADDR_DIVIDE, 0x1234);
        uint32_t div = apb_read(ADDR_DIVIDE);
        check("DIVIDER register", 0x1234, div, 0xFFFF);

        apb_write(ADDR_SS, 0xAB);
        uint32_t ss = apb_read(ADDR_SS);
        check("SS register", 0xAB, ss, 0xFF);

        printf("\n");
    }

    // Extra cycles for waveform completeness
    for (int i = 0; i < 20; i++) tick();

    // ─── Summary ────────────────────────────────────────
    printf("════════════════════════════════════════════════════\n");
    printf("  Results: %d passed, %d failed\n", test_pass, test_fail);
    printf("  Waveform: build/chisel_spi.vcd\n");
    printf("════════════════════════════════════════════════════\n");

    tfp->close();
    delete tfp;
    delete dut;
    delete contextp;

    return test_fail > 0 ? 1 : 0;
}
