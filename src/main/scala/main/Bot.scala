package main

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import cats.Id
import github4s.Github
import github4s.Github._
import github4s.free.domain._
import github4s.jvm.Implicits._
import scalaj.http.HttpResponse
import slack.SlackUtil
import slack.rtm.SlackRtmClient

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

object Bot {
  val dmChannels: mutable.MutableList[String] = mutable.MutableList[String]()
  val accessToken                             = sys.env("BOT_GITHUB_TOKEN")
  val techLeads                               = List("padge", "unrolled", "raulchedrese")

  def main(args: Array[String]): Unit = {
    val slackToken                            = sys.env("BOT_SLACK_TOKEN")
    implicit val system: ActorSystem          = ActorSystem("slack")
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    val client = SlackRtmClient(slackToken, new FiniteDuration(30, TimeUnit.SECONDS))
    val selfId = client.state.self.id

    client.onMessage { message =>
      //      val isDirectMessage: Boolean = SlackUtil.isDirectMsg(message)
      if (message.user != selfId)
        client.sendMessage(
          message.channel,
          callCommand(parseCommand(message.text))
        )
    }

  }

  def getPullRequests: List[PullRequest] = {
    val prFilters = List(PRFilterOpen, PRFilterSortPopularity)
    val listPullRequests =
      Github(Option(accessToken)).pullRequests.list("7shifts", "webapp", prFilters)

    listPullRequests.exec[cats.Id, HttpResponse[String]]() match {
      case Left(exception) => throw exception
      case Right(r)        => r.result
    }
  }

  def buildTechLeadApproval(approved: Boolean): List[String] =
    if (approved) {
      List.fill(1)("✨")
    } else {
      List.fill(1)("⚪")
    }

  def buildReviewStateStr(r: Review): String = {
    val slots            = 2
    val qa: List[String] = (if (r.labels.map(_.name).contains("QA passed")) "🎨" else "⚪️") :: Nil
    val tla = buildTechLeadApproval(r.techLeadApproval);
    if (r.approved > slots - 1) {
      val approvals = List.fill(if (r.approved > slots) slots else r.approved)("✅")
      val changes   = List.fill(r.changes)("❌")
      (approvals ::: changes ::: tla ::: qa).mkString(" ")
    } else {
      val requiredReviews = List.fill(slots - r.approved - r.changes)("⬜")
      val approvals       = List.fill(r.approved)("✅")
      val changes         = List.fill(r.changes)("❌")
      (approvals ::: changes ::: requiredReviews ::: tla ::: qa).mkString(" ")
    }
  }

  def buildTitle(r: Review): String =
    if (r.labels.map(_.name).contains("PRIORITY")) "🚨 *" + r.title + "* 🚨" else r.title

  def buildReviews(as: List[Issue]): List[String] =
    getReviews(as).foldLeft(List[String]())(
      (xs: List[String], r: Review) =>
        (buildReviewStateStr(r) :: r.url :: " – " + buildTitle(r) :: Nil)
          .mkString(" ") :: xs)

  private def getReviews(as: List[Issue]): List[Review] = {
    // I am pretty sure we could use fs2.async.parallelTraverse to do these concurrently
    val prLists = for (a: Issue <- as if a.state == "open")
      yield // Factor this out into a different method and return `main.Review`
      Github(Option(accessToken)).pullRequests
        .listReviews("7shifts", "webapp", a.number)
        .exec[Id, HttpResponse[String]]() match {
        case Left(exception) => throw exception
        case Right(r) =>
          val filteredReviews = filterUserReviews(r.result)
          Review(
            a.html_url,
            a.title,
            filteredReviews.count(p => p.state == PRRStateApproved),
            filteredReviews.count(p => p.state == PRRStateChangesRequested),
            filteredReviews.exists(fr => techLeads.contains(fr.user.get.login) && fr.state == PRRStateApproved),
            a.updated_at,
            a.created_at,
            a.closed_at,
            a.labels
          )
      }

    prLists
  }

  def filterUserReviews(as: List[PullRequestReview]): List[PullRequestReview] = {
    val mappedByUser: Map[Option[User], List[PullRequestReview]] =
      as.filter(_.user.nonEmpty)
        .filter(p => p.state == PRRStateChangesRequested || p.state == PRRStateApproved)
        .groupBy(_.user)

    mappedByUser.foldLeft(List[PullRequestReview]())((xs, kv) => {
      kv._2.sortBy(_.id).reverse.head :: xs
    })
  }

  def buildPullRequests(as: List[Issue]): String =
    if (as.isEmpty) "Could not find any open PRs"
    else buildReviews(as).mkString("\n")

  def getPullRequests(team: String): String = {
    val listPullRequests = Github(Option(accessToken)).issues.searchIssues(
      "",
      List(
        OwnerParamInRepository("7shifts/webapp"),
        IssueTypePullRequest,
        IssueStateOpen,
        LabelParam(team),
        SearchIn(Set(SearchInTitle))
      )
    )

    listPullRequests.exec[cats.Id, HttpResponse[String]]() match {
      case Left(exception) => throw exception
      case Right(r)        => buildPullRequests(r.result.items)
    }
  }

  def parseCommand(message: String): List[String] = {
    val Pattern = "!([a-z]+)".r
    val command = Pattern.findFirstMatchIn(message)

    command match {
      case Some(m) => m.toString() :: (message.replace(m.toString(), "").trim() :: Nil)
      case None    => throw new IllegalArgumentException("No command found.")
    }
  }

  def callCommand(l: List[String]): String = l.head match {
    case "!prs" => parseTeam(l.tail)
    case _      => throw new IllegalArgumentException("Command not recognized.")
  }

  def parseTeam(l: List[String]): String = l.head match {
    case rest => getPullRequests(rest.split(" ")(0))
  }

}
