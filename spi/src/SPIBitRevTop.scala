// SPDX-License-Identifier: Unlicense
package org.chipsalliance.spi

import chisel3._
import chisel3.experimental.hierarchy.instantiable
import chisel3.experimental.SerializableModule

class SPIBitRevInterface(parameter: SPIParameter) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(if (parameter.useAsyncReset) AsyncReset() else Bool())
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
  val ssPadO   = Output(UInt(parameter.ssNb.W))
  val sclkPadO = Output(Bool())
  val mosiPadO = Output(Bool())
  val misoPadO = Output(Bool())
}

@instantiable
class SPIBitRevTop(val parameter: SPIParameter)
    extends FixedIORawModule(new SPIBitRevInterface(parameter))
    with SerializableModule[SPIParameter]
    with ImplicitClock
    with ImplicitReset {
  override protected def implicitClock: Clock = io.clock
  override protected def implicitReset: Reset = io.reset

  val spi    = Module(new SPI(parameter))
  val bitrev = Module(new BitRev)

  spi.io.clock := io.clock
  spi.io.reset := io.reset

  spi.io.paddr   := io.paddr
  spi.io.psel    := io.psel
  spi.io.penable := io.penable
  spi.io.pwrite  := io.pwrite
  spi.io.pstrb   := io.pstrb
  spi.io.pwdata  := io.pwdata
  io.prdata      := spi.io.prdata
  io.pready      := spi.io.pready
  io.pslverr     := spi.io.pslverr
  io.intO        := spi.io.intO

  bitrev.io.sck  := spi.io.sclkPadO
  bitrev.io.mosi := spi.io.mosiPadO
  bitrev.io.ss   := spi.io.ssPadO(0)
  spi.io.misoPadI := bitrev.io.miso

  io.ssPadO   := spi.io.ssPadO
  io.sclkPadO := spi.io.sclkPadO
  io.mosiPadO := spi.io.mosiPadO
  io.misoPadO := bitrev.io.miso
}
