package scala.meta.internal.pc.completions

import scala.meta.internal.mtags.CoursierComplete
import scala.meta.internal.mtags.MtagsEnrichments.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourcePosition

object AmmoniteIvyCompletions:
  def contribute(
      selector: List[ImportSelector],
      completionPos: CompletionPos,
      text: String,
  )(using Context): List[CompletionValue] =
    val pos = completionPos.sourcePos
    val query = selector.collectFirst {
      case sel: ImportSelector
          if sel.sourcePos.encloses(pos) && sel.sourcePos.`end` > pos.`end` =>
        sel.name.decoded
    }
    query match
      case None => Nil
      case Some(dependency) =>
        val isInitialCompletion =
          pos.lineContent.trim == "import $ivy."
        val ivyEditRange =
          if isInitialCompletion then completionPos.toEditRange
          else
            // We need the text edit to span the whole group/artefact/version
            val (rangeStart, rangeEnd) =
              CoursierComplete.inferEditRange(pos.point, text)
            pos.withStart(rangeStart).withEnd(rangeEnd).toLsp
        val completions = CoursierComplete.complete(dependency)
        completions
          .map(insertText =>
            CompletionValue.IvyImport(
              insertText.stripPrefix(":"),
              Some(insertText),
              Some(ivyEditRange),
            )
          )
    end match
  end contribute
end AmmoniteIvyCompletions
