package ai.deepseek.dsh.android.data

import ai.deepseek.dsh.android.model.ProjectionFamily

/**
 * 写事务的**顺序证据**（G06）：`RoomDurableStore.commit` 把它实际执行的写入段落逐段登记到这里，
 * 测试因此可以断言“cursor 是最后一段”，而不是只相信注释。
 *
 * 生产装配用 [WriteJournal.None]（零开销）；测试用 [RecordingWriteJournal]。
 * 段落名是存储层的书写阶段，不是协议字符串。
 */
fun interface WriteJournal {
    fun section(section: CommitSection)

    companion object {
        /** 不记录：生产路径。 */
        val None = WriteJournal { }
    }
}

/**
 * 一次 `commit` 的固定写入顺序（G06 两条不变量的合成）：
 * `tombstone → baseline → raw frame → projection → outbox → cursor`，cursor 永远最后。
 *
 * 枚举的**声明顺序就是执行顺序**，[CommitSection.ordered] 依赖它，测试也依赖它：
 * 想调换顺序必须改这里，改不了调用方。
 */
enum class CommitSection {
    /** G06 reset 的第一步：基线里缺席的 pending key 先 tombstone。 */
    Tombstones,

    /** 基线确认到的 key 打世代标记（sweep 的 present 半边）。 */
    Baseline,

    /** 原始帧（durable raw log），投影的一切证据都来自它。 */
    Frames,

    /** head 位置 / islands / pending / resync / 诊断计数：D26 的区间合并已由调用方用 `Islands` 做完。 */
    Projection,

    /** 通知外发期望（D28：只有通知，没有写请求回执）。 */
    Outbox,

    /** 传输游标：永远是最后一段。 */
    Cursor,
    ;

    companion object {
        /** 固定执行顺序，测试断言用。 */
        val ordered: List<CommitSection> = entries.toList()
    }
}

/**
 * 崩溃注入：在指定段落**开始之前**抛出，模拟“事务写到一半进程死了”。
 * 因为整个批次在一个 Room 事务里，注入点之后（含 cursor）的写入都不该落库。
 */
fun interface CommitFault {
    /** 抛异常即中止本次事务。 */
    fun before(section: CommitSection)

    companion object {
        val None = CommitFault { }
    }
}

/**
 * 同时充当 [WriteJournal] 与 [CommitFault]：记录每次 commit 的段落序列，并可安排一次“某段之前失败”。
 *
 * 故障在**登记之前**抛出，所以被注入失败的那一段不会出现在记录里 ——
 * 记录下来的就是“真正执行过的写入”，正好是 G06 崩溃安全断言要的东西。
 */
class RecordingWriteJournal : WriteJournal, CommitFault {

    private val commits = mutableListOf<MutableList<CommitSection>>()
    private var current: MutableList<CommitSection>? = null
    private var plan: FaultPlan? = null

    /** 已开始的 commit 序号（0 基）：故障计划按它命中，与“已经记录了几个段落”无关。 */
    private var round = -1

    /** 让第 [index] 次 commit（从 0 计）在第 [section] 段之前失败一次。 */
    fun failAt(
        index: Int,
        section: CommitSection,
        error: Throwable = IllegalStateException("injected fault before $section"),
    ) {
        plan = FaultPlan(index, section, error)
    }

    /** 开始一个新的段落列表：每次 commit 调一次。 */
    fun beginCommit() {
        round += 1
        current = null
    }

    override fun before(section: CommitSection) {
        val scheduled = plan ?: return
        // 没显式 beginCommit 时也算第一轮：故障注入不能因为调用姿势而静默失效。
        val roundNow = if (round < 0) 0 else round
        if (scheduled.section != section || scheduled.round != roundNow) return
        plan = null
        throw scheduled.error
    }

    override fun section(section: CommitSection) {
        val list = current ?: mutableListOf<CommitSection>().also {
            commits += it
            current = it
        }
        check(section !in list) { "section $section written twice in one commit" }
        list += section
    }

    /** 每次 commit 记录的段落序列。 */
    fun orders(): List<List<CommitSection>> = commits.map { it.toList() }

    fun lastOrder(): List<CommitSection> = commits.lastOrNull()?.toList() ?: emptyList()

    /** 段落名序列（失败信息更好读）。 */
    fun render(): List<String> = commits.map { order -> order.joinToString("->") { it.name } }

    private data class FaultPlan(val round: Int, val section: CommitSection, val error: Throwable)
}

/**
 * 投影落地面记账：v1 schema 没有 `ProjectionUpsert` 的通用表（D07 的“投影是纯函数、可从原始帧重建”
 * 才是权威路径）。落得下族的族由存储层直接写各自的表，落不下的族交给这里，绝不静默丢弃。
 */
fun interface ProjectionSink {
    fun consume(families: Map<ProjectionFamily, Int>)

    companion object {
        /** 默认：什么都不记（生产路径）。 */
        val None = ProjectionSink { }

        /** 测试用：累计族计数。 */
        val Counting = ProjectionSink { }
    }
}
