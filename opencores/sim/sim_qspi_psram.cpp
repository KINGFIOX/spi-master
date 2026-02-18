// sim_qspi_psram.cpp
// QSPI Master + PSRAM Slave interaction test (Verilator)
// Wiring done in Chisel (QSPIPSRAMTop). C++ drives APB and implements DPI-C.
//
// Test plan:
//   1. Write individual bytes via APB, read back and verify
//   2. Write full 32-bit words via APB, read back and verify
//   3. Write half-words via APB, read back and verify
//   4. Write a pattern, read back to test data integrity

#include "VQSPIPSRAMTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// ─── PSRAM memory model (DPI-C implementation) ─────────────────
static uint8_t psram_mem[1 << 20]; // 1 MB PSRAM

extern "C" void psram_read(int addr, char *data) {
  uint32_t a = (uint32_t)addr & 0xFFFFF;
  *data = (char)psram_mem[a];
}

extern "C" void psram_write(int addr, char data) {
  uint32_t a = (uint32_t)addr & 0xFFFFF;
  psram_mem[a] = (uint8_t)data;
  printf("write@0x%03X: %02X\n", a, psram_mem[a]);
}

// ─── Simulation globals ────────────────────────────────────────
static VQSPIPSRAMTop *dut = nullptr;
static VerilatedVcdC *tfp = nullptr;
static uint64_t sim_time = 0;
static int test_pass = 0;
static int test_fail = 0;

static constexpr int MAX_CYCLES = 500000;

// ─── Clock tick ────────────────────────────────────────────────
static void tick() {
  dut->clock = 1;
  dut->eval();
  if (tfp) tfp->dump(sim_time++);
  dut->clock = 0;
  dut->eval();
  if (tfp) tfp->dump(sim_time++);
}

// ─── Reset ─────────────────────────────────────────────────────
static void do_reset() {
  dut->reset = 0;
  dut->clock = 0;
  dut->psel = 0;
  dut->penable = 0;
  dut->pwrite = 0;
  dut->pstrb = 0;
  dut->paddr = 0;
  dut->pwdata = 0;
  dut->eval();
  dut->reset = 1;
  for (int i = 0; i < 10; i++) tick();
  dut->reset = 0;
  tick();
}

// ─── APB write (waits for pready) ──────────────────────────────
//
// APB timing (per ARM IHI 0024E, Figure 3-5):
//   T0 (SETUP):  PSEL=1, PENABLE=0.
//   T1 (ACCESS): PENABLE rises to 1.
//   T2..Tn:      Wait states while PREADY=0. PENABLE stays 1.
//   Tn+1:        PREADY=1. Transfer completes on this rising edge.
//   Tn+2:        Master deasserts PENABLE (IDLE or next SETUP).
//
// In Verilator, pready goes high combinationally when the FSM enters
// the 'done' state. The done→idle transition fires on the NEXT posedge
// when penable is still 1. So we hold penable=1 for one more tick()
// after seeing pready=1, matching the real protocol where the master
// reacts one cycle after sampling pready.
//
static void apb_write(uint32_t addr, uint32_t data, uint8_t strb = 0xF) {
  // SETUP phase
  dut->paddr = addr;
  dut->pwdata = data;
  dut->pstrb = strb;
  dut->pwrite = 1;
  dut->psel = 1;
  dut->penable = 0;
  tick();
  // ACCESS phase
  dut->penable = 1;
  int cycles = 0;
  do {
    tick();
    if (++cycles > MAX_CYCLES) {
      printf("  TIMEOUT: apb_write(0x%08X) did not complete\n", addr);
      break;
    }
  } while (!dut->pready);
  // pready sampled high; hold penable one more cycle for done→idle
  tick();
  // IDLE phase
  dut->psel = 0;
  dut->penable = 0;
  dut->pwrite = 0;
  tick();
}

// ─── APB read (waits for pready) ───────────────────────────────
static uint32_t apb_read(uint32_t addr) {
  // SETUP phase
  dut->paddr = addr;
  dut->pwrite = 0;
  dut->pstrb = 0xF;
  dut->psel = 1;
  dut->penable = 0;
  tick();
  // ACCESS phase
  dut->penable = 1;
  int cycles = 0;
  do {
    tick();
    if (++cycles > MAX_CYCLES) {
      printf("  TIMEOUT: apb_read(0x%08X) did not complete\n", addr);
      return 0xDEADBEEF;
    }
  } while (!dut->pready);
  uint32_t val = dut->prdata;
  // pready sampled high; hold penable one more cycle for done→idle
  tick();
  // IDLE phase
  dut->psel = 0;
  dut->penable = 0;
  tick();
  return val;
}

// ─── Check helper ──────────────────────────────────────────────
static void check(const char *name, uint32_t expected, uint32_t actual,
                  uint32_t mask = 0xFFFFFFFF) {
  actual &= mask;
  expected &= mask;
  if (actual == expected) {
    printf("  PASS %s: expected 0x%08X, got 0x%08X\n", name, expected, actual);
    test_pass++;
  } else {
    printf("  FAIL %s: expected 0x%08X, got 0x%08X\n", name, expected, actual);
    test_fail++;
  }
}

// ═══════════════════════════════════════════════════════════════
//  Main
// ═══════════════════════════════════════════════════════════════
int main(int argc, char **argv) {
  VerilatedContext *contextp = new VerilatedContext;
  contextp->commandArgs(argc, argv);
  contextp->traceEverOn(true);

  dut = new VQSPIPSRAMTop{contextp};
  tfp = new VerilatedVcdC;
  dut->trace(tfp, 99);
  tfp->open("build/qspi_psram.vcd");

  memset(psram_mem, 0, sizeof(psram_mem));

  printf("====================================================\n");
  printf("  QSPI Master + PSRAM Slave Simulation\n");
  printf("  Memory-mapped transparent flash controller test\n");
  printf("====================================================\n\n");

  do_reset();
  printf("[time %5lu] reset done\n\n", (unsigned long)sim_time);

  // ─── Test 1: Single byte writes + word read ─────────────
  {
    printf("-- Test 1: Write individual bytes, read back as word --\n");
    uint32_t base = 0x100;

    apb_write(base, 0x000000AA, 0x1); // pstrb=0001 → byte 0
    apb_write(base, 0x0000BB00, 0x2); // pstrb=0010 → byte 1
    apb_write(base, 0x00CC0000, 0x4); // pstrb=0100 → byte 2
    apb_write(base, 0xDD000000, 0x8); // pstrb=1000 → byte 3
    printf("@0x%03X: %02X %02X %02X %02X\n", base, psram_mem[base], psram_mem[base+1], psram_mem[base+2], psram_mem[base+3]);
    uint32_t rd = apb_read(base);
    check("byte writes → word read", 0xDDCCBBAA, rd);
    printf("\n");
  }

  // ─── Test 2: Full word write + read ─────────────────────
  {
    printf("-- Test 2: Full word write + read --\n");
    uint32_t base = 0x200;

    apb_write(base, 0x04030201, 0xF);
    uint32_t rd = apb_read(base);
    check("word write/read", 0x04030201, rd);
    printf("\n");
  }

  // ─── Test 3: Half-word writes + word read ───────────────
  {
    printf("-- Test 3: Half-word writes + word read --\n");
    uint32_t base = 0x300;

    apb_write(base, 0x00002211, 0x3); // pstrb=0011 -> lower half
    apb_write(base, 0x44330000, 0xC); // pstrb=1100 -> upper half

    uint32_t rd = apb_read(base);
    check("half-word writes -> word read", 0x44332211, rd);
    printf("\n");
  }

  // ─── Test 4: Multiple words write + read ────────────────
  {
    printf("-- Test 4: Multiple word write/read at different addresses --\n");
    uint32_t addrs[] = {0x400, 0x404, 0x408, 0x40C};
    uint32_t vals[] = {0xDEADBEEF, 0xCAFEBABE, 0x12345678, 0xA5A5A5A5};

    for (int i = 0; i < 4; i++) {
      apb_write(addrs[i], vals[i], 0xF);
    }

    for (int i = 0; i < 4; i++) {
      uint32_t rd = apb_read(addrs[i]);
      char name[64];
      snprintf(name, sizeof(name), "multi-word[%d] @0x%03X", i, addrs[i]);
      check(name, vals[i], rd);
    }
    printf("\n");
  }

  // ─── Test 5: Overwrite and re-read ──────────────────────
  {
    printf("-- Test 5: Overwrite existing data --\n");
    uint32_t base = 0x200;

    apb_write(base, 0xFEDCBA98, 0xF);
    uint32_t rd = apb_read(base);
    check("overwrite word", 0xFEDCBA98, rd);
    printf("\n");
  }

  // ─── Test 6: Zero and all-ones ──────────────────────────
  {
    printf("-- Test 6: Edge cases (zero and all-ones) --\n");
    uint32_t base = 0x500;

    apb_write(base, 0x00000000, 0xF);
    uint32_t rd0 = apb_read(base);
    check("write zero", 0x00000000, rd0);

    apb_write(base, 0xFFFFFFFF, 0xF);
    uint32_t rd1 = apb_read(base);
    check("write all-ones", 0xFFFFFFFF, rd1);
    printf("\n");
  }

  // Cool-down
  for (int i = 0; i < 20; i++)
    tick();

  printf("====================================================\n");
  printf("  Results: %d passed, %d failed\n", test_pass, test_fail);
  printf("  Waveform: build/qspi_psram.vcd\n");
  printf("====================================================\n");

  tfp->close();
  delete tfp;
  delete dut;
  delete contextp;
  return test_fail > 0 ? 1 : 0;
}
