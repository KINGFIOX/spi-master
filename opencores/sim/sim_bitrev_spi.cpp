// sim_bitrev_spi.cpp
// SPI Master + BitRev Slave interaction test (Verilator)
// Wiring done in Chisel (SPIBitRevTop). C++ only drives APB.
// 16-bit SPI transfer: upper 8 bits to slave, lower 8 bits reversed back
// SPI Mode 0: CPOL=0, CPHA=0 (tx_neg=1, rx_neg=0)

#include "VSPIBitRevTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <cstdio>
#include <cstdlib>
#include <cstdint>

static constexpr uint8_t ADDR_TX0    = 0 << 2;
static constexpr uint8_t ADDR_CTRL   = 4 << 2; // 16
static constexpr uint8_t ADDR_DIVIDE = 5 << 2;
static constexpr uint8_t ADDR_SS     = 6 << 2;

static constexpr uint32_t CTRL_GO     = 1 << 8;
static constexpr uint32_t CTRL_TX_NEG = 1 << 10;
static constexpr uint32_t CTRL_ASS    = 1 << 13;

static VSPIBitRevTop* dut = nullptr;
static VerilatedVcdC* tfp = nullptr;
static uint64_t       sim_time = 0;
static int            test_pass = 0;
static int            test_fail = 0;

static void tick() {
    dut->clock = 1;
    dut->eval();
    if (tfp) tfp->dump(sim_time++);
    dut->clock = 0;
    dut->eval();
    if (tfp) tfp->dump(sim_time++);
}

static void do_reset() {
    dut->reset    = 1;
    dut->psel     = 0;
    dut->penable  = 0;
    dut->pwrite   = 0;
    dut->pstrb    = 0;
    dut->paddr    = 0;
    dut->pwdata   = 0;
    for (int i = 0; i < 10; i++) tick();
    dut->reset = 0;
    tick();
}

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
    uint32_t val = dut->prdata;
    // IDLE phase: PSEL=0, PENABLE=0
    dut->psel    = 0;
    dut->penable = 0;
    tick();
    return val;
}

static void check(const char* name, uint32_t expected, uint32_t actual,
                  uint32_t mask = 0xFFFFFFFF) {
    actual   &= mask;
    expected &= mask;
    if (actual == expected) {
        printf("  PASS %s: expected 0x%02X, got 0x%02X\n", name, expected, actual);
        test_pass++;
    } else {
        printf("  FAIL %s: expected 0x%02X, got 0x%02X\n", name, expected, actual);
        test_fail++;
    }
}

static uint8_t bit_reverse(uint8_t b) {
    b = (uint8_t)(((b & 0xF0) >> 4) | ((b & 0x0F) << 4));
    b = (uint8_t)(((b & 0xCC) >> 2) | ((b & 0x33) << 2));
    b = (uint8_t)(((b & 0xAA) >> 1) | ((b & 0x55) << 1));
    return b;
}

static uint8_t bitrev_transfer(uint8_t tx_byte, uint32_t divider) {
    apb_write(ADDR_DIVIDE, divider);
    apb_write(ADDR_SS, 0x01);
    apb_write(ADDR_TX0, (uint32_t)tx_byte << 8);
    uint32_t ctrl_base = 16 /*charLen*/ | CTRL_ASS | CTRL_TX_NEG;
    apb_write(ADDR_CTRL, ctrl_base);
    apb_write(ADDR_CTRL, ctrl_base | CTRL_GO);
    // pready blocks TX/RX reads during transfer — no polling needed.
    // apb_read will wait until the transfer finishes, then return RX data.
    uint32_t rx = apb_read(ADDR_TX0);
    return (uint8_t)(rx & 0xFF);
}

int main(int argc, char** argv) {
    VerilatedContext* contextp = new VerilatedContext;
    contextp->commandArgs(argc, argv);
    contextp->traceEverOn(true);

    dut = new VSPIBitRevTop{contextp};
    tfp = new VerilatedVcdC;
    dut->trace(tfp, 99);
    tfp->open("build/bitrev_spi.vcd");

    printf("====================================================\n");
    printf("  SPI Master + BitRev Slave (Chisel wiring)\n");
    printf("  Mode: CPOL=0, CPHA=0 (tx_neg=1, rx_neg=0)\n");
    printf("====================================================\n\n");

    do_reset();
    printf("[time %5lu] reset done\n\n", (unsigned long)sim_time);

    {
        printf("-- Warmup: bitrev(0xFF), divider=4 --\n");
        uint8_t rx = bitrev_transfer(0xFF, 0);
        uint8_t exp = bit_reverse(0xFF);
        printf("  TX = 0xFF, RX = 0x%02X (expected 0x%02X)\n", rx, exp);
        check("warmup bitrev(0xFF)", exp, rx, 0xFF);
        printf("\n");
    }

    struct TestCase { uint8_t tx; const char* name; };
    TestCase tests[] = {
        {0x53, "bitrev(0x53)"},
        {0xA5, "bitrev(0xA5)"},
        {0x01, "bitrev(0x01)"},
        {0x80, "bitrev(0x80)"},
        {0xFF, "bitrev(0xFF)"},
        {0x00, "bitrev(0x00)"},
        {0x0F, "bitrev(0x0F)"},
        {0x55, "bitrev(0x55)"},
    };

    for (const auto& tc : tests) {
        printf("-- Test: %s --\n", tc.name);
        uint8_t rx  = bitrev_transfer(tc.tx, 0);
        uint8_t exp = bit_reverse(tc.tx);
        printf("  TX = 0x%02X -> reversed = 0x%02X, RX = 0x%02X\n",
               tc.tx, exp, rx);
        check(tc.name, exp, rx, 0xFF);
        printf("\n");
    }

    for (int i = 0; i < 20; i++) tick();

    printf("====================================================\n");
    printf("  Results: %d passed, %d failed\n", test_pass, test_fail);
    printf("  Waveform: build/bitrev_spi.vcd\n");
    printf("====================================================\n");

    tfp->close();
    delete tfp;
    delete dut;
    delete contextp;
    return test_fail > 0 ? 1 : 0;
}
