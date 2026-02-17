// SPDX-License-Identifier: Unlicense
// Chisel rewrite of OpenCores QSPI Master (APB bus interface)

package org.chipsalliance.qspi

import chisel3._
import chisel3.util._
import chisel3.experimental.hierarchy.{instantiable, public, Instance, Instantiate}
import chisel3.experimental.{SerializableModule, SerializableModuleParameter, Analog}
import chisel3.probe.{define, Probe, ProbeValue}
import chisel3.properties.{AnyClassType, Class, Property}

// ═══════════════════════════════════════════════════════════════════
// Parameter
// cofigs/QSPI.json, used for the parameter deserialize from json to case class
// ═══════════════════════════════════════════════════════════════════

object QSPIParameter {
  implicit def rwP: upickle.default.ReadWriter[QSPIParameter] =
    upickle.default.macroRW
}

/** Parameter of [[QSPI]].
  *
  * @param dividerLen
  *   Width of the clock divider register (8, 16, 24, or 32).
  * @param maxChar
  *   Maximum bits per QSPI transfer (8, 16, 24, 32, 64, or 128).
  * @param ssNb
  *   Number of slave-select lines (1–32).
  * @param useAsyncReset
  *   Use asynchronous reset when true.
  */
case class QSPIParameter(
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

/** Verification IO of [[QSPI]]. */
class QSPIProbe(parameter: QSPIParameter) extends Bundle {
  val tip = Bool()
}

// ═══════════════════════════════════════════════════════════════════
// Object Model (metadata)
// used to export metadata to downstream toolchains
// ═══════════════════════════════════════════════════════════════════

/** Metadata of [[QSPI]]. */
@instantiable
class QSPIOM(parameter: QSPIParameter) extends Class {
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

class QSPIIO extends Bundle {
  val sck = Output(Bool())
  val ce_n = Output(Bool())
  val dio = Analog(4.W)
}

/** Interface of [[QSPI]]. */
class QSPIInterface(parameter: QSPIParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  // APB slave
  val apb  = new APBSlaveIO
  val intO = Output(Bool())

  // QSPI master
  val qspiio = new QSPIIO

  // Verification & metadata
  val probe = Output(Probe(new QSPIProbe(parameter), layers.Verification))
  val om    = Output(Property[AnyClassType]())
}

// ═══════════════════════════════════════════════════════════════════
// Clock Generator
// ═══════════════════════════════════════════════════════════════════

/** QSPI serial clock generator.
  *
  * Divides the system clock by `2*(divider+1)` to produce `clkOut`.
  * Also generates single-cycle `posEdge` / `negEdge` strobes one
  * system-clock cycle before the corresponding edge of `clkOut`.
  */
class QSPIClgen(dividerLen: Int) extends Module {
  val io = IO(new Bundle {
    val go      = Input(Bool())
    val tip     = Input(Bool())
    val lastClk = Input(Bool())
    val divider = Input(UInt(dividerLen.W))
    val clkOut  = Output(Bool())
    val posEdge = Output(Bool())
    val negEdge = Output(Bool())
  })

  // Counter (reset to all-1s so the first period after reset is the longest)
  private val cnt    = RegInit(((1L << dividerLen) - 1).U(dividerLen.W))
  private val clkOut = RegInit(false.B)

  private val cntZero = cnt === 0.U
  private val cntOne  = cnt === 1.U
  private val divZero = !io.divider.orR

  // Counter counts half period
  when(!io.tip || cntZero) {
    cnt := io.divider
  }.otherwise {
    cnt := cnt - 1.U
  }

  // clk_out toggles every other half period
  // ( !io.lastCLK || clkOut ) <=> ( io.lastClk -> clkOut )
  // 如果是最后一次, 那么只有当 clkOut = 1 时, 才翻转, 这意味着最后一次永远是 1->0
  when(io.tip && cntZero && (!io.lastClk || clkOut)) {
    clkOut := ~clkOut
  }

  // Positive-edge / negative-edge strobes (registered)
  io.posEdge := RegNext(
    (io.tip && !clkOut && cntOne) ||
      (divZero && clkOut) ||
      (divZero && io.go && !io.tip),
    false.B
  )

  io.negEdge := RegNext(
    (io.tip && clkOut && cntOne) ||
      (divZero && !clkOut && io.tip),
    false.B
  )

  io.clkOut := clkOut
}

// ═══════════════════════════════════════════════════════════════════
// Shift Register  (corresponds to qspi_shift.v)
// ═══════════════════════════════════════════════════════════════════

/** QSPI shift register.
  *
  * Handles parallel load from the bus (byte-lane writes), serial
  * output (MOSI), and serial input (MISO) with configurable MSB/LSB
  * first ordering and configurable TX/RX clock edges.
  */
class QSPIShift(parameter: QSPIParameter) extends Module {
  private val cBits = parameter.charLenBits
  private val mChar = parameter.maxChar

  val io = IO(new Bundle {
    val len4       = Input(UInt(cBits.W)) // charLen (0 = no transfer)
    val go        = Input(Bool())
    val posEdge   = Input(Bool())
    val negEdge   = Input(Bool())
    val tip       = Output(Bool())  // transfer in progress
    val last      = Output(Bool())  // last bit
    val wen       = Input(Bool()) // write the data reg inside ?
    val pIn       = Input(UInt(mChar.W)) // parallel input
    val pOut      = Output(UInt(mChar.W)) // parallel output
    val sClk      = Input(Bool()) // serial clock
    val sIn       = Input(UInt(4.W)) // serial input
    val sOut      = Output(UInt(4.W)) // serial output
    val sOutEn    = Output(Bool())
  })

  // ─── State ──────────────────────────────────────────────────
  private val cnt  = RegInit(0.U(cBits.W)) // bit counter
  private val data = RegInit(VecInit(Seq.fill(mChar >> 2)( 0.U(4.W) ))) // shift register
  private val sOut = RegInit(false.B)
  private val tip  = RegInit(false.B)

  // ─── Combinational ─────────────────────────────────────────
  private val last = !cnt.orR // cnt == 0

  // Bit positions for TX and RX
  private val txBitPos = cnt - 1.U
  private val rxBitPos = cnt - 1.U

  private val rxClk = io.posEdge && !last
  dontTouch(rxClk)
  private val txClk = io.negEdge && !last
  dontTouch(txClk)

  // ─── Bit counter ────────────────────────────────────────────
  when(tip) {
    when(io.posEdge) { cnt := cnt - 1.U }
  }.otherwise {
    cnt := io.len4
  }

  // ─── Transfer in progress ──────────────────────────────────
  when(io.go && !tip && io.len4.orR) {
    tip := true.B
  }.elsewhen(tip && last && io.posEdge) {
    tip := false.B
  }

  // ─── TX: send bits to line ─────────────────────────────────
  when(txClk || !tip) {
    sOut := data(txBitPos)
  }

  // ─── Data register: parallel load / serial receive ─────────
  when(!tip && io.wen) {
    data := io.pIn
  }.otherwise {
    // Serial receive: sample MISO at rxBitPos
    when(rxClk) {
      data(rxBitPos) := io.sIn
    }
  }

  // ─── Outputs ────────────────────────────────────────────────
  io.pOut := data.asUInt
  io.tip  := tip
  io.last := last
  io.sOut := sOut
}

// ═══════════════════════════════════════════════════════════════════
// QSPI Top  (corresponds to qspi_top.v)
// ═══════════════════════════════════════════════════════════════════

/** Hardware Implementation of QSPI Master with APB slave interface.
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
  *   - [charLenBits-1:0]  CHAR_LEN   character length (0 = no transfer)
  *   - [7]                reserved
  *   - [8]                GO         start transfer (auto-clears)
  *   - [9]                RX_NEGEDGE sample MISO on falling edge
  *   - [10]               TX_NEGEDGE drive  MOSI on falling edge
  *   - [11]               LSB        send LSB first
  *   - [12]               IE         interrupt enable
  *   - [13]               ASS        automatic slave select
  */
@instantiable
class QSPI(val parameter: QSPIParameter)
    extends FixedIORawModule(new QSPIInterface(parameter))
    with SerializableModule[QSPIParameter]
    with ImplicitClock
    with ImplicitReset {

  // ─── meta ──────────────────────────────────────────────
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset
  private val P = parameter
  private val mChar = P.maxChar // 128
  private val cBits = P.charLenBits // 7
  private val qspiWriteCmd = "h38".U(8.W)
  private val qspiReadCmd = "heb".U(8.W)
  assert( io.apb.paddr(1, 0) === 0.U, "Error: QSPI read/write address must be aligned to 4 bytes." )

  // ─── TriState Gate ──────────────────────────────────────────────
  private val mosiEnOut = WireDefault(false.B)
  private val mosiOut = WireDefault( 0.U(4.W) )
  private val miso = TriStateInBuf( io.qspiio.dio, mosiOut, mosiEnOut )

  // ─── configs ──────────────────────────────────────────────
  private val divider = RegInit(((1L << P.dividerLen) - 1).U(P.dividerLen.W)) // default 0xff

  // ─── register ────────────────────────────────────────────
  private val qpi = RegInit(false.B)

  // ─── Sub-modules, instantiate ─────────────────────────────
  private val clgen = Module(new QSPIClgen(P.dividerLen))
  private val shift = Module(new QSPIShift(P))

  // ─── Sub-modules, connections ─────────────────────────────
  shift.io.len4 := 0.U
  shift.io.go := false.B
  shift.io.posEdge := clgen.io.posEdge
  shift.io.negEdge := clgen.io.negEdge
  shift.io.wen := false.B // default
  shift.io.pIn := 0.U
  shift.io.sClk := clgen.io.clkOut
  shift.io.sIn := miso
  mosiOut := shift.io.sOut
  mosiEnOut := shift.io.sOutEn
  clgen.io.go := false.B
  clgen.io.tip := shift.io.tip
  clgen.io.divider := divider
  clgen.io.lastClk := shift.io.last

  // --- write calculation ------------------------------------------------------------
  private val wdata = WireDefault( 0.U( mChar.W ) )
  private val wCharLen4 = WireDefault( 0.U( cBits.W ) )
  switch (io.apb.pstrb) {
    is( "b0001".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0), io.apb.pwdata(7, 0) ); wCharLen4 := ( (32 + 24 + 8) >> 2 ).U }
    is( "b0010".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0) + 1.U, io.apb.pwdata(15, 8) ); wCharLen4 := ( (32 + 24 + 8) >> 2 ).U }
    is( "b0100".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0) + 2.U, io.apb.pwdata(23, 16) ); wCharLen4 := ( (32 + 24 + 8) >> 2 ).U }
    is( "b1000".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0) + 3.U, io.apb.pwdata(31, 24) ); wCharLen4 := ( (32 + 24 + 8) >> 2 ).U }
    is( "b0011".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0), io.apb.pwdata(15, 0) ); wCharLen4 := ( (32 + 24 + 16) >> 2 ).U }
    is( "b1100".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0) + 2.U, io.apb.pwdata(31, 16) ); wCharLen4 := ( (32 + 24 + 16) >> 2 ).U }
    is( "b1111".U ) { wdata := Cat( qspiWriteCmd, io.apb.paddr(23, 0), io.apb.pwdata(31, 0) ); wCharLen4 := ( (32 + 24 + 32) >> 2 ).U }
  }

  // ─── state machine ──────────────────────────────────────────────
  object State extends ChiselEnum {
    val idle, setup, access, ready = Value
  }
  private val state = RegInit(State.idle)
  private val pready_reg = RegInit(false.B)
  switch(state) {
    is(State.idle) {
      pready_reg := false.B
      when(io.apb.psel && !io.apb.penable) {
        state := State.setup
      }
    }
    is(State.setup) {
      assert(!qpi, "Error: QPI access detected but not supported.")
      val next_charLen4 = WireDefault( 0.U(cBits.W) )
      val next_data = WireDefault( 0.U(mChar.W) )
      when(io.apb.pwrite) {
        next_charLen4 := wCharLen4
        next_data := wdata
      } .otherwise {
        next_charLen4 := ( (32 + 24 + 24 + 32) >> 2 ).U // read
        next_data := Cat( qspiReadCmd, io.apb.paddr(23, 0), 0.U(24.W), 0.U(32.W) )
      }
      shift.io.wen := true.B
      shift.io.len4 := next_charLen4
      shift.io.pIn := next_data
      state := State.access
    }
    is(State.access) {
      clgen.io.go := true.B
      shift.io.go := true.B
      when(shift.io.last) {
        state := State.ready
      }
    }
    is(State.ready) {
      io.apb.pready := true.B
      when(io.apb.penable) {
        state := State.idle
      }
    }
  }

  // ─── Probe ──────────────────────────────────────────────────
  private val probeWire: QSPIProbe = Wire(new QSPIProbe(parameter))
  define(io.probe, ProbeValue(probeWire))
  probeWire.tip := shift.io.tip

  // ─── Object Model ──────────────────────────────────────────
  private val omInstance: Instance[QSPIOM] = Instantiate(new QSPIOM(parameter))
  io.om := omInstance.getPropertyReference.asAnyClassType
}
