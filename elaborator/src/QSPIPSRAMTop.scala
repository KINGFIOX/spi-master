// SPDX-License-Identifier: Unlicense
package org.chipsalliance.spi.elaborator

import mainargs._
import org.chipsalliance.qspi.{QSPIPSRAMTop, QSPIParameter}
import chisel3.experimental.util.SerializableModuleElaborator

object QSPIPSRAMTopMain extends SerializableModuleElaborator {
  val topName = "QSPIPSRAMTop"

  implicit object PathRead extends TokensReader.Simple[os.Path] {
    def shortName = "path"
    def read(strs: Seq[String]) = Right(os.Path(strs.head, os.pwd))
  }

  @main
  case class QSPIParameterMain(
    @arg(name = "dividerLen") dividerLen: Int = 16,
    @arg(name = "maxChar") maxChar: Int = 128,
    @arg(name = "ssNb") ssNb: Int = 8,
    @arg(name = "useAsyncReset") useAsyncReset: Boolean = false
  ) {
    def convert: QSPIParameter = QSPIParameter(dividerLen, maxChar, ssNb, useAsyncReset)
  }

  implicit def QSPIParameterMainParser: ParserForClass[QSPIParameterMain] =
    ParserForClass[QSPIParameterMain]

  @main
  def config(
    @arg(name = "parameter") parameter: QSPIParameterMain,
    @arg(name = "target-dir") targetDir: os.Path = os.pwd
  ) =
    os.write.over(targetDir / s"${topName}.json", configImpl(parameter.convert))

  @main
  def design(
    @arg(name = "parameter") parameter: os.Path,
    @arg(name = "target-dir") targetDir: os.Path = os.pwd
  ) = {
    val (firrtl, annos) = designImpl[QSPIPSRAMTop, QSPIParameter](os.read.stream(parameter))
    os.write.over(targetDir / s"${topName}.fir", firrtl)
    os.write.over(targetDir / s"${topName}.anno.json", annos)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
