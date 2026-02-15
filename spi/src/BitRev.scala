// SPDX-License-Identifier: Unlicense
package org.chipsalliance.spi

import chisel3._
import chisel3.util._

class SPIIO(ssNb: Int) extends Bundle {
  val sck  = Output(Bool())
  val mosi = Output(Bool())
  val miso = Input(Bool())
  val ss   = Output(UInt(ssNb.W))
}

class BitRev extends RawModule {
  val io = IO(Flipped(new SPIIO(1)))

  val reset = io.ss.asBool.asAsyncReset
  val clock = io.sck.asClock
  val clockN = ( !io.sck ).asClock

  val impl = withClockAndReset(clock, reset) { Module(new Impl) }
  val miso = withClockAndReset(clockN, reset) { RegNext(impl.io.miso) }

  class Impl extends Module with RequireAsyncReset {
    val io = IO(new Bundle {
      val miso = Output(Bool())
      val mosi = Input(Bool())
    })

    object State extends ChiselEnum {
      val Read, Write = Value
    }

    val state   = RegInit(State.Read)
    val counter = Counter(8)
    val data    = RegInit(0.U(8.W))

    switch(state) {
      is(State.Read) {
        when(counter.inc()) {
          state := State.Write
        }
      }
      is(State.Write) { }
    }

    when(state === State.Read) {
      data := Cat(data(6, 0), io.mosi)
    }.otherwise {
      data := Cat(0.U(1.W), data(7, 1))
    }

    io.miso := data(0)
  }

  io.miso := Mux(io.ss.asBool, true.B, miso)
  impl.io.mosi := io.mosi
}
