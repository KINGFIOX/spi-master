# Chisel Probe 机制说明

## 什么是 Probe

`SPIProbe` 是 Chisel 的 **Verification Probe** 机制，用于在不影响综合结果的前提下，向外暴露模块内部信号供验证使用。

## 代码结构

### 1. 定义 Probe Bundle

```scala
/** Verification IO of [[SPI]]. */
class SPIProbe(parameter: SPIParameter) extends Bundle {
  val tip = Bool()
}
```

`tip`（transfer in progress）是 `SPIShift` 子模块内部的信号，表示 SPI 传输正在进行中。

### 2. 在接口中声明 Probe 端口

```scala
class SPIInterface(parameter: SPIParameter) extends Bundle {
  // ...
  // Verification & metadata
  val probe = Output(Probe(new SPIProbe(parameter), layers.Verification))
  val om    = Output(Property[AnyClassType]())
}
```

`Probe(bundle, layers.Verification)` 表示这个端口只存在于**验证层（Verification Layer）**中。

### 3. 在顶层模块中连接 Probe

```scala
// ─── Probe ──────────────────────────────────────────────
val probeWire: SPIProbe = Wire(new SPIProbe(parameter))
define(io.probe, ProbeValue(probeWire))
probeWire.tip := tip
```

通过 `define` 和 `ProbeValue` 将内部信号 `tip` 绑定到 probe 端口。

## 核心特性：条件编译

`layers.Verification` 是条件编译的关键。在 nix 构建流水线中，firtool 通过 `--enable-layers` 参数控制：

```bash
# 仿真时：启用 Verification 层 → probe 信号存在，可以观测
firtool ... --enable-layers Verification

# 综合时：不启用 → probe 信号被完全移除，零面积开销
firtool ...
```

## 与直接加输出端口的区别

|                    | 普通 Output 端口 | Probe 端口                 |
| ------------------ | ---------------- | -------------------------- |
| **综合后是否存在** | 是，永远占用引脚 | 否，综合时完全移除         |
| **仿真中可见**     | 是               | 仅在启用 Verification 层时 |
| **硬件开销**       | 有（引脚、布线） | 零                         |
| **用途**           | 功能接口         | 调试、验证                 |

## 实际应用场景

目前 `SPIProbe` 只暴露了 `tip`，但可以扩展它来暴露更多内部状态，例如：

```scala
class SPIProbe(parameter: SPIParameter) extends Bundle {
  val tip      = Bool()                               // 传输进行中
  val cnt      = UInt((parameter.charLenBits + 1).W)  // 当前位计数器
  val shiftReg = UInt(parameter.maxChar.W)             // 移位寄存器内容
}
```

这样在仿真或形式验证中可以编写 assertion 或 cover property 来检查内部行为，而综合出来的硬件完全不受影响。

## 补充：SPIOM（Object Model）

```scala
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
```

`SPIOM` 是类似的概念，但用于**元数据**而非信号。它把参数（dividerLen、maxChar 等）作为 Property 暴露给工具链读取，比如自动生成文档或寄存器描述文件，同样不会综合成硬件。
