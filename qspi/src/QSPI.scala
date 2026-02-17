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
// configs/QSPI.json, used for the parameter deserialize from json to case class
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
  val sck  = Output(Bool())
  val ce_n = Output(Bool())
  val dio  = Analog(4.W)
}

/** Interface of [[QSPI]]. */
class QSPIInterface(parameter: QSPIParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())

  // APB slave (32-bit address for memory-mapped flash access)
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

  private val cnt    = RegInit(0.U(dividerLen.W))
  private val clkOut = RegInit(false.B)

  private val cntZero = cnt === 0.U
  private val cntOne  = cnt === 1.U
  private val divZero = !io.divider.orR

  when(!io.tip || cntZero) {
    cnt := io.divider
  }.otherwise {
    cnt := cnt - 1.U
  }

  // clkOut toggles every half period;
  // lastClk → clkOut ensures the final edge is always 1→0
  when(io.tip && cntZero && (!io.lastClk || clkOut)) {
    clkOut := ~clkOut
  }

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
// Shift Register
// ═══════════════════════════════════════════════════════════════════

class QSPIShift(parameter: QSPIParameter) extends Module {
  private val cBits    = parameter.charLenBits // 7
  private val mChar    = parameter.maxChar // 128
  private val nNibbles = mChar >> 2 // 32 (number of 4bit)
  private val idxBits  = log2Ceil(nNibbles) // 5

  val io = IO(new Bundle {
    val len4    = Input(UInt(cBits.W))  // total nibbles to transfer
    val sOutLen = Input(UInt(cBits.W))  // nibbles with output enabled
    val go      = Input(Bool())
    val posEdge = Input(Bool())
    val negEdge = Input(Bool())
    val tip     = Output(Bool())        // transfer in progress
    val last    = Output(Bool())        // last nibble (cnt == 0)
    val wen     = Input(Bool())         // parallel load enable
    val pIn     = Input(UInt(mChar.W))  // parallel input
    val pOut    = Output(UInt(mChar.W)) // parallel output
    val sClk    = Input(Bool())         // serial clock (unused, reserved)
    val sIn     = Input(UInt(4.W))      // serial input  (DIO read)
    val sOut    = Output(UInt(4.W))     // serial output (DIO write)
    val sOutEn  = Output(Bool())        // output enable for DIO
  })

  // ─── Registers ────────────────────────────────────────────────
  private val data    = RegInit(VecInit(Seq.fill(nNibbles)(0.U(4.W))))
  private val sOut    = RegInit(0.U(4.W))
  private val cnt     = RegInit(0.U(cBits.W))
  private val regLen4 = RegInit(0.U(cBits.W))
  private val outCnt  = RegInit(0.U(cBits.W))

  // ─── FSM ──────────────────────────────────────────────────────
  private object State extends ChiselEnum {
    val idle, mosi, miso = Value
  }
  private val state = RegInit(State.idle)

  // ─── Combinational ───────────────────────────────────────────
  private val last   = !cnt.orR
  private val bitPos = (cnt - 1.U)(idxBits - 1, 0)
  private val rxClk  = io.posEdge && !last
  dontTouch(rxClk)
  private val txClk  = io.negEdge && !last
  dontTouch(txClk)

  // ─── State machine ───────────────────────────────────────────
  switch(state) {
    is(State.idle) {
      cnt := regLen4

      when(io.wen) {
        for (i <- 0 until nNibbles) {
          data(i) := io.pIn(i * 4 + 3, i * 4)
        }
        regLen4 := io.len4
        outCnt  := io.sOutLen

        val pInNibbles = VecInit((0 until nNibbles).map(i => io.pIn(i * 4 + 3, i * 4)))
        val firstTxIdx = (io.len4 - 1.U)(idxBits - 1, 0)
        sOut := pInNibbles(firstTxIdx)
      }

      when(io.go && regLen4.orR) {
        state := State.mosi
      }
    }

    is(State.mosi) {
      when(io.posEdge) { cnt := cnt - 1.U }
      when(rxClk)      { data(bitPos) := io.sIn }
      when(txClk) {
        sOut := data(bitPos)
        when(outCnt.orR) { outCnt := outCnt - 1.U }
      }

      when(last && io.posEdge) {
        state := State.idle
      }.elsewhen(!outCnt.orR) {
        state := State.miso
      }
    }

    is(State.miso) {
      when(io.posEdge) { cnt := cnt - 1.U }
      when(rxClk)      { data(bitPos) := io.sIn }

      when(last && io.posEdge) {
        state := State.idle
      }
    }
  }

  // ─── Outputs ──────────────────────────────────────────────────
  io.pOut   := data.asUInt
  io.tip    := state =/= State.idle
  io.last   := last
  io.sOut   := sOut
  io.sOutEn := state === State.mosi
}

// ═══════════════════════════════════════════════════════════════════
// QSPI Top
// ═══════════════════════════════════════════════════════════════════

@instantiable
class QSPI(val parameter: QSPIParameter)
    extends FixedIORawModule(new QSPIInterface(parameter))
    with SerializableModule[QSPIParameter]
    with ImplicitClock
    with ImplicitReset {

  // ─── meta ──────────────────────────────────────────────
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset
  private val P     = parameter
  private val mChar = P.maxChar
  private val cBits = P.charLenBits

  // ─── Expand 8-bit SPI command to 32-bit nibble format ─────
  // Each command bit becomes a 4-bit nibble with the bit at [0].
  // e.g. 0x38 (00111000) → 0x00111000 (8 nibbles: 0,0,1,1,1,0,0,0)
  private def expandCmd(cmd: Int): BigInt = {
    var result: BigInt = 0
    for (i <- 7 to 0 by -1) {
      result = (result << 4) | ((cmd >> i) & 1)
    }
    result
  }
  private val qspiWriteCmdExp = expandCmd(0x38).U(32.W) // write: quad input
  private val qspiReadCmdExp  = expandCmd(0xEB).U(32.W) // read:  fast read quad I/O

  assert(io.apb.paddr(1, 0) === 0.U, "Error: QSPI read/write address must be aligned to 4 bytes.")

  // ─── TriState Gate ──────────────────────────────────────────
  private val mosiEnOut = WireDefault(false.B)
  private val mosiOut   = WireDefault(0.U(4.W))
  private val miso      = TriStateInBuf(io.qspiio.dio, mosiOut, mosiEnOut)

  // ─── Config registers ──────────────────────────────────────
  // Default divider = 0 → SCK = system_clock / 2 (fastest)
  private val divider = RegInit(4.U(P.dividerLen.W))

  // ─── Sub-modules ───────────────────────────────────────────
  private val clgen = Module(new QSPIClgen(P.dividerLen))
  private val shift = Module(new QSPIShift(P))

  // ─── Sub-modules: default connections ──────────────────────
  shift.io.len4    := 0.U
  shift.io.sOutLen := 0.U
  shift.io.go      := false.B
  shift.io.posEdge := clgen.io.posEdge
  shift.io.negEdge := clgen.io.negEdge
  shift.io.wen     := false.B
  shift.io.pIn     := 0.U
  shift.io.sClk    := clgen.io.clkOut
  shift.io.sIn     := miso
  mosiOut          := shift.io.sOut
  mosiEnOut        := shift.io.sOutEn

  clgen.io.go      := false.B
  clgen.io.tip     := shift.io.tip
  clgen.io.divider := divider
  clgen.io.lastClk := shift.io.last

  // ─── APB default outputs ──────────────────────────────────
  io.apb.pready  := false.B
  io.apb.prdata  := 0.U
  io.apb.pslverr := false.B
  io.intO        := false.B

  // ─── QSPI outputs ────────────────────────────────────────
  io.qspiio.sck := clgen.io.clkOut

  // ─── Read/write tracking ──────────────────────────────────
  private val isWriteReg = RegInit(false.B)

  // ─── Write data calculation (little-endian byte swap) ─────
  // For multi-byte writes, bytes are reordered so that
  // the lowest APB byte lane maps to the lowest flash address.
  private val wdata     = WireDefault(0.U(mChar.W))
  private val wCharLen4 = WireDefault(0.U(cBits.W))

  switch(io.apb.pstrb) {
    is("b0001".U) {
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0), io.apb.pwdata(7, 0))
      wCharLen4 := ((32 + 24 + 8) >> 2).U
    }
    is("b0010".U) {
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0) + 1.U, io.apb.pwdata(15, 8))
      wCharLen4 := ((32 + 24 + 8) >> 2).U
    }
    is("b0100".U) {
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0) + 2.U, io.apb.pwdata(23, 16))
      wCharLen4 := ((32 + 24 + 8) >> 2).U
    }
    is("b1000".U) {
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0) + 3.U, io.apb.pwdata(31, 24))
      wCharLen4 := ((32 + 24 + 8) >> 2).U
    }
    is("b0011".U) {
      val swapped = Cat(io.apb.pwdata(7, 0), io.apb.pwdata(15, 8))
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0), swapped)
      wCharLen4 := ((32 + 24 + 16) >> 2).U
    }
    is("b1100".U) {
      val swapped = Cat(io.apb.pwdata(23, 16), io.apb.pwdata(31, 24))
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0) + 2.U, swapped)
      wCharLen4 := ((32 + 24 + 16) >> 2).U
    }
    is("b1111".U) {
      val swapped = Cat(io.apb.pwdata(7, 0), io.apb.pwdata(15, 8), io.apb.pwdata(23, 16), io.apb.pwdata(31, 24))
      wdata     := Cat(qspiWriteCmdExp, io.apb.paddr(23, 0), swapped)
      wCharLen4 := ((32 + 24 + 32) >> 2).U
    }
  }

  // ─── Transfer-complete detection ─────────────────────────
  // Fires at the LAST cycle where tip is still true (same cycle
  // as shift FSM's last && posEdge transition), so QSPI top and
  // shift FSM transition in lockstep — no retrigger gap.
  private val tipDone = shift.io.tip && shift.io.last && clgen.io.posEdge

  // ─── State machine ────────────────────────────────────────
  object State extends ChiselEnum {
    val idle, setup, access, ready = Value
  }
  private val state = RegInit(State.idle)

  switch(state) {
    is(State.idle) {
      when(io.apb.psel && !io.apb.penable) {
        state := State.setup
      }
    }

    is(State.setup) {
      isWriteReg := io.apb.pwrite

      val nextCharLen4 = WireDefault(0.U(cBits.W))
      val nextData     = WireDefault(0.U(mChar.W))
      val nextSOutLen4  = WireDefault(0.U(cBits.W))

      // mux
      when(io.apb.pwrite) {
        nextCharLen4 := wCharLen4
        nextData     := wdata
        nextSOutLen4  := wCharLen4
      }.otherwise {
        // Read: cmd(32) + addr(24) + wait(24) + rxdata(32) = 112 bits = 28 nibbles
        nextCharLen4 := ((32 + 24 + 24 + 32) >> 2).U
        nextData     := Cat(qspiReadCmdExp, io.apb.paddr(23, 0), 0.U(24.W), 0.U(32.W))
        nextSOutLen4  := ((32 + 24) >> 2).U
      }

      when(nextCharLen4 === 0.U) {
        // Unsupported pstrb or zero-length → skip transfer
        state := State.ready
      }.otherwise {
        shift.io.wen     := true.B
        shift.io.len4    := nextCharLen4
        shift.io.pIn     := nextData
        shift.io.sOutLen := nextSOutLen4
        state            := State.access
      }
    }

    is(State.access) {
      shift.io.go := true.B
      clgen.io.go := true.B
      when(tipDone) {
        state := State.ready
      }
    }

    is(State.ready) {
      io.apb.pready := true.B

      // For reads: reassemble the 32bit word from the 4 nibbles
      when(!isWriteReg) {
        val rd = shift.io.pOut(31, 0)
        io.apb.prdata := Cat(rd(7, 0), rd(15, 8), rd(23, 16), rd(31, 24))
      }

      when(io.apb.penable) {
        state := State.idle
      }
    }
  }

  io.qspiio.ce_n := ! ( state === State.access )

  // ─── Probe ──────────────────────────────────────────────────
  private val probeWire: QSPIProbe = Wire(new QSPIProbe(parameter))
  define(io.probe, ProbeValue(probeWire))
  probeWire.tip := shift.io.tip

  // ─── Object Model ──────────────────────────────────────────
  private val omInstance: Instance[QSPIOM] = Instantiate(new QSPIOM(parameter))
  io.om := omInstance.getPropertyReference.asAnyClassType
}
