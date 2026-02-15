// SPDX-License-Identifier: Unlicense
package org.chipsalliance.spi.elaborator

import mainargs._
import org.chipsalliance.spi.{SPIBitRevTop, SPIParameter}
import chisel3.experimental.util.SerializableModuleElaborator

object SPIBitRevTopMain extends SerializableModuleElaborator {
  val topName = "SPIBitRevTop"

  implicit object PathRead extends TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(strs: Seq[String]) = Right(os.Path(strs.head, os.pwd))
  }

  @main
  case class SPIParameterMain(
    @arg(name = "dividerLen") dividerLen: Int = 16,
    @arg(name = "maxChar") maxChar: Int = 128,
    @arg(name = "ssNb") ssNb: Int = 8,
    @arg(name = "useAsyncReset") useAsyncReset: Boolean = false
  ) {
    def convert: SPIParameter = SPIParameter(dividerLen, maxChar, ssNb, useAsyncReset)
  }

  implicit def SPIParameterMainParser: ParserForClass[SPIParameterMain] =
    ParserForClass[SPIParameterMain]

  @main
  def config(
    @arg(name = "parameter") parameter: SPIParameterMain,
    @arg(name = "target-dir") targetDir: os.Path = os.pwd
  ) =
    os.write.over(targetDir / s"${topName}.json", configImpl(parameter.convert))

  @main
  def design(
    @arg(name = "parameter") parameter: os.Path,
    @arg(name = "target-dir") targetDir: os.Path = os.pwd
  ) = {
    val (firrtl, annos) = designImpl[SPIBitRevTop, SPIParameter](os.read.stream(parameter))
    os.write.over(targetDir / s"${topName}.fir", firrtl)
    os.write.over(targetDir / s"${topName}.anno.json", annos)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
