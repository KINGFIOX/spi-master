// SPDX-License-Identifier: Unlicense
// Chisel rewrite of OpenCores SPI Master (APB bus interface)

package org.chipsalliance.spi

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, public, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.probe.{define, Probe, ProbeValue}
import chisel3.properties.{AnyClassType, Class, Property}

// ═══════════════════════════════════════════════════════════════════
// Parameter
// cofigs/SPI.json, used for the parameter deserialize from json to case class
// ═══════════════════════════════════════════════════════════════════

object SPIParameter {
  implicit def rwP: upickle.default.ReadWriter[SPIParameter] =
    upickle.default.macroRW
}

/** Parameter of [[SPI]].
  *
  * @param dividerLen
  *   Width of the clock divider register (8, 16, 24, or 32).
  * @param maxChar
  *   Maximum bits per SPI transfer (8, 16, 24, 32, 64, or 128).
  * @param ssNb
  *   Number of slave-select lines (1–32).
  * @param useAsyncReset
  *   Use asynchronous reset when true.
  */
case class SPIParameter(
  dividerLen:    Int     = 16,
  maxChar:       Int     = 128,
  ssNb:          Int     = 8,
  useAsyncReset: Boolean = false
) extends SerializableModuleParameter {
  require(Seq(8, 16, 24, 32).contains(dividerLen), "dividerLen must be 8, 16, 24, or 32")
  require(Seq(8, 16, 24, 32, 64, 128).contains(maxChar), "maxChar must be 8, 16, 24, 32, 64, or 128")
  require(ssNb >= 1 && ssNb <= 32, "ssNb must be in 1..32")

  /** Number of bits needed to encode the character length field. */
  val charLenBits: Int = log2Ceil(maxChar) // 7 for 128

  /** Number of 32-bit TX/RX data words. */
  val nTxWords: Int = (maxChar + 31) / 32 // 4 for 128

  /** Width of the control register. */
  val ctrlBitNb: Int = 14
}

// ═══════════════════════════════════════════════════════════════════
// Probe (verification)
// ═══════════════════════════════════════════════════════════════════

/** Verification IO of [[SPI]]. */
class SPIProbe(parameter: SPIParameter) extends Bundle {
  val tip = Bool()
}

// ═══════════════════════════════════════════════════════════════════
// Object Model (metadata)
// used to export metadata to downstream toolchains
// ═══════════════════════════════════════════════════════════════════

/** Metadata of [[SPI]]. */
@instantiable
class SPIOM(parameter: SPIParameter) extends Class {
  val dividerLen:    Property[Int]     = IO(Output(Property[Int]()))
  val maxChar:       Property[Int]     = IO(Output(Property[Int]()))
  val ssNb:          Property[Int]     = IO(Output(Property[Int]()))
  val useAsyncReset: Property[Boolean] = IO(Output(Property[Boolean]()))
  dividerLen    := Property(parameter.dividerLen)
  maxChar       := Property(parameter.maxChar)
  ssNb          := Property(parameter.ssNb)
  useAsyncReset := Property(parameter.useAsyncReset)
}

// ═══════════════════════════════════════════════════════════════════
// Interface
// ═══════════════════════════════════════════════════════════════════

/** Interface of [[SPI]]. */
class SPIInterface(parameter: SPIParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  // APB slave
  val paddr   = Input(UInt(5.W))
  val psel    = Input(Bool())
  val penable = Input(Bool())
  val pwrite  = Input(Bool())
  val pstrb   = Input(UInt(4.W))
  val pwdata  = Input(UInt(32.W))
  val prdata  = Output(UInt(32.W))
  val pready  = Output(Bool())
  val pslverr = Output(Bool())
  val intO    = Output(Bool())

  // SPI master
  val ssPadO   = Output(UInt(parameter.ssNb.W))
  val sclkPadO = Output(Bool())
  val mosiPadO = Output(Bool())
  val misoPadI = Input(Bool())

  // Verification & metadata
  val probe = Output(Probe(new SPIProbe(parameter), layers.Verification))
  val om    = Output(Property[AnyClassType]())
}

// ═══════════════════════════════════════════════════════════════════
// Clock Generator  (corresponds to spi_clgen.v)
// ═══════════════════════════════════════════════════════════════════

/** SPI serial clock generator.
  *
  * Divides the system clock by `2*(divider+1)` to produce `clkOut`.
  * Also generates single-cycle `posEdge` / `negEdge` strobes one
  * system-clock cycle before the corresponding edge of `clkOut`.
  */
class SPIClgen(dividerLen: Int) extends Module {
  val io = IO(new Bundle {
    val go      = Input(Bool())
    val enable  = Input(Bool())
    val lastClk = Input(Bool())
    val divider = Input(UInt(dividerLen.W))
    val clkOut  = Output(Bool())
    val posEdge = Output(Bool())
    val negEdge = Output(Bool())
  })

  // Counter (reset to all-1s so the first period after reset is the longest)
  val cnt    = RegInit(((1L << dividerLen) - 1).U(dividerLen.W))
  val clkOut = RegInit(false.B)

  val cntZero = cnt === 0.U
  val cntOne  = cnt === 1.U
  val divZero = !io.divider.orR

  // Counter counts half period
  when(!io.enable || cntZero) {
    cnt := io.divider
  }.otherwise {
    cnt := cnt - 1.U
  }

  // clk_out toggles every other half period
  when(io.enable && cntZero && (!io.lastClk || clkOut)) {
    clkOut := ~clkOut
  }

  // Positive-edge / negative-edge strobes (registered)
  io.posEdge := RegNext(
    (io.enable && !clkOut && cntOne) ||
      (divZero && clkOut) ||
      (divZero && io.go && !io.enable),
    false.B
  )

  io.negEdge := RegNext(
    (io.enable && clkOut && cntOne) ||
      (divZero && !clkOut && io.enable),
    false.B
  )

  io.clkOut := clkOut
}

// ═══════════════════════════════════════════════════════════════════
// Shift Register  (corresponds to spi_shift.v)
// ═══════════════════════════════════════════════════════════════════

/** SPI shift register.
  *
  * Handles parallel load from the bus (byte-lane writes), serial
  * output (MOSI), and serial input (MISO) with configurable MSB/LSB
  * first ordering and configurable TX/RX clock edges.
  */
class SPIShift(parameter: SPIParameter) extends Module {
  private val cBits = parameter.charLenBits
  private val mChar = parameter.maxChar

  val io = IO(new Bundle {
    val latch     = Input(UInt(4.W))     // per-word load enable
    val byteSel   = Input(UInt(4.W))     // byte-lane strobes
    val len       = Input(UInt(cBits.W)) // charLen (0 = maxChar)
    val go        = Input(Bool())
    val posEdge   = Input(Bool())
    val negEdge   = Input(Bool())
    val rxNegedge = Input(Bool())
    val txNegedge = Input(Bool())
    val tip       = Output(Bool())  // transfer in progress
    val last      = Output(Bool())  // last bit
    val pIn       = Input(UInt(32.W)) // parallel input
    val pOut      = Output(UInt(mChar.W)) // parallel output
    val sClk      = Input(Bool()) // serial clock
    val sIn       = Input(Bool()) // serial input
    val sOut      = Output(Bool()) // serial output
  })

  // ─── State ──────────────────────────────────────────────────
  val cnt  = RegInit(0.U((cBits + 1).W)) // bit counter
  val data = RegInit(VecInit(Seq.fill(mChar)(false.B))) // shift register
  val sOut = RegInit(false.B)
  val tip  = RegInit(false.B)

  // ─── Combinational ─────────────────────────────────────────
  val last = !cnt.orR // cnt == 0

  // Extend len: 0 → maxChar, n → n  (cBits+1 bits wide)
  val lenExt = Cat(!io.len.orR, io.len)

  // Bit positions for TX and RX
  val txBitPos = cnt - 1.U
  val rxBitPos = Mux(io.rxNegedge, cnt, cnt - 1.U)

  // Sampling clocks
  val rxClk = Mux(io.rxNegedge, io.negEdge, io.posEdge) && (!last || io.sClk)
  val txClk = Mux(io.txNegedge, io.negEdge, io.posEdge) && !last

  // ─── Bit counter ────────────────────────────────────────────
  when(tip) {
    when(io.posEdge) { cnt := cnt - 1.U }
  }.otherwise {
    cnt := Mux(
      !io.len.orR,
      Cat(1.U(1.W), 0.U(cBits.W)), // len=0 → maxChar
      Cat(0.U(1.W), io.len)
    )
  }

  // ─── Transfer in progress ──────────────────────────────────
  when(io.go && !tip) {
    tip := true.B
  }.elsewhen(tip && last && io.posEdge) {
    tip := false.B
  }

  // ─── TX: send bits to line ─────────────────────────────────
  when(txClk || !tip) {
    sOut := data(txBitPos(cBits - 1, 0))
  }

  // ─── Data register: parallel load / serial receive ─────────
  when(!tip && io.latch.orR) {
    // Parallel load from bus (byte-lane writes to each 32-bit word)
    for (wordIdx <- 0 until parameter.nTxWords) { // 0,1,2,3
      when(io.latch(wordIdx)) {
        for (byteIdx <- 0 until 4) { // 0,1,2,3. each word is 32 bits, so 4 bytes
          // Guard: only generate logic for bytes within data width
          if ((wordIdx * 32 + (byteIdx + 1) * 8) <= mChar) {
            when(io.byteSel(byteIdx)) {
              for (bitIdx <- 0 until 8) {
                data(wordIdx * 32 + byteIdx * 8 + bitIdx) := io.pIn(byteIdx * 8 + bitIdx)
              }
            }
          }
        }
      }
    }
  }.otherwise {
    // Serial receive: sample MISO at rxBitPos
    when(rxClk) {
      data(rxBitPos(cBits - 1, 0)) := io.sIn
    }
  }

  // ─── Outputs ────────────────────────────────────────────────
  io.pOut := data.asUInt
  io.tip  := tip
  io.last := last
  io.sOut := sOut
}

// ═══════════════════════════════════════════════════════════════════
// SPI Top  (corresponds to spi_top.v)
// ═══════════════════════════════════════════════════════════════════

/** Hardware Implementation of SPI Master with APB slave interface.
  *
  * Register map (byte address → word offset via `paddr[4:2]`):
  *   - 0: TX_0 / RX_0  (bits  31:0   of shift register)
  *   - 1: TX_1 / RX_1  (bits  63:32)
  *   - 2: TX_2 / RX_2  (bits  95:64)
  *   - 3: TX_3 / RX_3  (bits 127:96)
  *   - 4: CTRL
  *   - 5: DIVIDER
  *   - 6: SS
  *
  * CTRL register layout:
  *   - [charLenBits-1:0]  CHAR_LEN   character length (0 = maxChar)
  *   - [7]                reserved
  *   - [8]                GO         start transfer (auto-clears)
  *   - [9]                RX_NEGEDGE sample MISO on falling edge
  *   - [10]               TX_NEGEDGE drive  MOSI on falling edge
  *   - [11]               LSB        send LSB first
  *   - [12]               IE         interrupt enable
  *   - [13]               ASS        automatic slave select
  */
@instantiable
class SPI(val parameter: SPIParameter)
    extends FixedIORawModule(new SPIInterface(parameter))
    with SerializableModule[SPIParameter]
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  private val P = parameter

  // ─── Registers ──────────────────────────────────────────────
  val divider = RegInit(((1L << P.dividerLen) - 1).U(P.dividerLen.W))
  val ctrl    = RegInit(0.U(P.ctrlBitNb.W))
  val ss      = RegInit(0.U(P.ssNb.W))
  val intReg  = RegInit(false.B)

  // ─── Ctrl field extraction ─────────────────────────────────
  val charLen   = ctrl(P.charLenBits - 1, 0)
  val go        = ctrl(8)
  val rxNegedge = ctrl(9)
  val txNegedge = ctrl(10)
  val ie        = ctrl(12)
  val ass       = ctrl(13)

  // ─── APB decode ─────────────────────────────────────────────
  val regAddr  = io.paddr(4, 2) // byte address → word offset
  val regWrite = io.psel & io.penable & io.pwrite

  io.pslverr := false.B

  // Address selects
  val spiDividerSel = io.psel & (regAddr === 5.U)
  val spiCtrlSel    = io.psel & (regAddr === 4.U)
  val spiSsSel      = io.psel & (regAddr === 6.U)
  val spiTxSel      = VecInit((0 until 4).map(i => io.psel & (regAddr === i.U)))

  // ─── Sub-modules ────────────────────────────────────────────
  val clgen = Module(new SPIClgen(P.dividerLen))
  val shift = Module(new SPIShift(P))

  val tip     = shift.io.tip
  val lastBit = shift.io.last
  val posEdge = clgen.io.posEdge
  val negEdge = clgen.io.negEdge
  val rx      = shift.io.pOut // maxChar-bit receive data

  // Flow control during SPI transfer (tip=1):
  //   - Writes to any register:  blocked (pready=0), otherwise silently dropped.
  //   - Reads of TX/RX data (addr 0-3): blocked (pready=0), shift register
  //     is actively changing and would return torn/inconsistent data.
  //   - Reads of CTRL/DIVIDER/SS (addr 4-6): allowed (pready=1),
  //     so software can poll CTRL GO bit to know when transfer finishes.
  val isTxAddr = regAddr < 4.U
  io.pready := !tip || (!io.pwrite && !isTxAddr)

  // Clgen connections
  clgen.io.go      := go
  clgen.io.enable  := tip
  clgen.io.lastClk := lastBit
  clgen.io.divider := divider

  // Shift connections
  shift.io.len       := charLen
  shift.io.latch     := spiTxSel.asUInt & Fill(4, io.penable & io.pwrite)
  shift.io.byteSel   := io.pstrb
  shift.io.go        := go
  shift.io.posEdge   := posEdge
  shift.io.negEdge   := negEdge
  shift.io.rxNegedge := rxNegedge
  shift.io.txNegedge := txNegedge
  shift.io.pIn       := io.pwdata
  shift.io.sClk      := clgen.io.clkOut
  shift.io.sIn       := io.misoPadI

  // ─── Read mux (combinational) ──────────────────────────────
  val prdataMux = WireDefault(0.U(32.W))
  for (i <- 0 until P.nTxWords) { // 0,1,2,3
    when(regAddr === i.U) {
      val hi = math.min(i * 32 + 31, P.maxChar - 1)
      val lo = i * 32
      if (hi - lo + 1 == 32) {
        prdataMux := rx(hi, lo)
      } else {
        prdataMux := rx(hi, lo).pad(32)
      }
    }
  }
  when(regAddr === 4.U) { prdataMux := ctrl.pad(32) }
  when(regAddr === 5.U) { prdataMux := divider.pad(32) }
  when(regAddr === 6.U) { prdataMux := ss.pad(32) }
  io.prdata := prdataMux

  // ─── Interrupt ──────────────────────────────────────────────
  when(ie && tip && lastBit && posEdge) {
    intReg := true.B
  }.elsewhen(io.psel && io.penable) { // reset
    intReg := false.B
  }
  io.intO := intReg

  // ─── Divider register (byte-lane write, locked during tip) ─
  when(regWrite && spiDividerSel && !tip) {
    val nBytes = P.dividerLen / 8 // 2
    val mask   = Cat((0 until nBytes).reverse/*1,0*/.map(j => Fill(8, io.pstrb(j))))
    divider := (io.pwdata(P.dividerLen - 1, 0) & mask) | (divider & ~mask)
  }

  // ─── Ctrl register ─────────────────────────────────────────
  when(regWrite && spiCtrlSel && !tip) {
    val fullMask = Cat(Fill(8, io.pstrb(1)), Fill(8, io.pstrb(0)))
    val mask     = fullMask(P.ctrlBitNb - 1, 0)
    ctrl := (io.pwdata(P.ctrlBitNb - 1, 0) & mask) | (ctrl & ~mask)
  }.elsewhen(tip && lastBit && posEdge) {
    // Auto-clear GO bit at end of transfer
    ctrl := ctrl & ~(1 << 8).U(P.ctrlBitNb.W)
  }

  // ─── Slave select register (single-byte write) ─────────────
  when(regWrite && spiSsSel && !tip) {
    when(io.pstrb(0)) { ss := io.pwdata(P.ssNb - 1, 0) }
  }

  // ─── SPI outputs ────────────────────────────────────────────
  io.sclkPadO := clgen.io.clkOut
  io.mosiPadO := shift.io.sOut
  // ASS mode: assert ss only during transfer; otherwise always assert
  io.ssPadO := ~(ss & Fill(P.ssNb, (tip & ass) | !ass))

  // ─── Probe ──────────────────────────────────────────────────
  val probeWire: SPIProbe = Wire(new SPIProbe(parameter))
  define(io.probe, ProbeValue(probeWire))
  probeWire.tip := tip

  // ─── Object Model ──────────────────────────────────────────
  val omInstance: Instance[SPIOM] = Instantiate(new SPIOM(parameter))
  io.om := omInstance.getPropertyReference.asAnyClassType
}
