package tests

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import munit.Location
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j

abstract class BaseCallHierarchySuite(name: String) extends BaseLspSuite(name) {

  protected def libraryDependencies: List[String] = Nil

  trait CallGetter[C] {
    def getCalls(item: CallHierarchyItem): Future[List[C]]
    def getItem(call: C): CallHierarchyItem
    def getFromRanges(call: C): List[lsp4j.Range]
  }

  private val incomingCallGetter: CallGetter[CallHierarchyIncomingCall] =
    new CallGetter[CallHierarchyIncomingCall] {
      def getCalls(
          item: CallHierarchyItem
      ): Future[List[CallHierarchyIncomingCall]] = server.incomingCalls(item)

      def getItem(call: CallHierarchyIncomingCall): CallHierarchyItem =
        call.getFrom()

      def getFromRanges(call: CallHierarchyIncomingCall): List[lsp4j.Range] =
        call.getFromRanges().asScala.toList
    }

  private val outgoingCallGetter: CallGetter[CallHierarchyOutgoingCall] =
    new CallGetter[CallHierarchyOutgoingCall] {
      def getCalls(
          item: CallHierarchyItem
      ): Future[List[CallHierarchyOutgoingCall]] = server.outgoingCalls(item)

      def getItem(call: CallHierarchyOutgoingCall): CallHierarchyItem =
        call.getTo()

      def getFromRanges(call: CallHierarchyOutgoingCall): List[lsp4j.Range] =
        call.getFromRanges().asScala.toList
    }

  private def assertCallHierarchy[C](
      callGetter: CallGetter[C],
      input: String,
      scalaVersion: Option[String],
      item: Option[CallHierarchyItem],
  )(implicit
      loc: Location
  ): Future[Map[String, CallHierarchyItem]] = {
    val files = FileLayout.mapFromString(input)
    val (filename, edit) =
      if (item.isEmpty)
        files
          .find(_._2.contains("@@"))
          .map { case (fileName, code) =>
            (fileName, code.replaceAll("""(<<|>>|<\?<|>\?>)""", ""))
          }
          .getOrElse {
            throw new IllegalArgumentException(
              "No `@@` was defined that specifies cursor position"
            )
          }
      else ("", "") // never used

    val pattern = """(<\??<)(\w*)(>\??>)(\/\*(\d*)\*\/)""".r

    val identifiers = files
      .map { case (_, code) =>
        pattern.findAllMatchIn(code).map(_.group(5))
      }
      .flatten
      .toSet

    val base = files.map { case (fileName, code) =>
      fileName -> code.replaceAll("""(<<|>>|<\?<|>\?>|@@)""", "")
    }

    val expected = files.map { case (fileName, code) =>
      val codeWithoutCursorPos = code.replaceAll("@@", "")
      fileName -> identifiers
        .map(id =>
          id -> pattern.replaceAllIn(
            codeWithoutCursorPos,
            m =>
              if (m.group(5) == id) m.toString
              else m.group(2) + m.group(4),
          )
        )
        .toMap
    }

    val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)

    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":
           |  {
           |    "scalaVersion" : "$actualScalaVersion",
           |    "libraryDependencies": ${toJsonArray(libraryDependencies)}
           |  }
           |}
           |${input
            .replaceAll("""(<<|>>|<\?<|>\?>|@@)""", "")}""".stripMargin
      )
      _ <- Future.sequence(
        files.map(file => server.didOpen(s"${file._1}"))
      )
      items <- item match {
        case item @ Some(_) => Future.successful(item)
        case None => server.prepareCallHierarchy(filename, edit)
      }
      calls <- items match {
        case Some(item) =>
          callGetter.getCalls(item)
        case _ =>
          Future.successful(Nil)
      }
      (remainingCalls, items) = identifiers.foldLeft(
        (calls, Map.empty[String, CallHierarchyItem])
      ) { case ((calls, checked), id) =>
        val (remaining, item) = server.assertCallHierarchy(
          expected.map { case (fileName, codes) => fileName -> codes(id) },
          base,
          calls,
          callGetter.getItem _,
          callGetter.getFromRanges _,
        )
        (remaining, checked + (id -> item))
      }
      _ = assert(remainingCalls.isEmpty)
    } yield items
  }

  def assertIncomingCalls(
      input: String,
      item: Option[CallHierarchyItem] = None,
      scalaVersion: Option[String] = None,
  )(implicit loc: Location) = assertCallHierarchy(
    incomingCallGetter,
    input,
    scalaVersion,
    item,
  )

  def assertOutgoingCalls(
      input: String,
      item: Option[CallHierarchyItem] = None,
      scalaVersion: Option[String] = None,
  )(implicit loc: Location) = assertCallHierarchy(
    outgoingCallGetter,
    input,
    scalaVersion,
    item,
  )
}
