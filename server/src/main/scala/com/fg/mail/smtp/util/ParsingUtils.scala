package com.fg.mail.smtp.util

import scala.collection.mutable.ListMap
import scala.util.matching.Regex
import java.io.File
import java.math.BigDecimal
import scala.collection.immutable.TreeSet
import org.slf4j.LoggerFactory
import scala.Some
import com.fg.mail.smtp.Options

/**
 *
 * @author Jakub Liška (liska@fg.cz), FG Forrest a.s. (c) 2013
 * @version $Id: 12/7/13 1:01 PM u_jli Exp $
 */
object ParsingUtils {

  val log = LoggerFactory.getLogger(getClass)

  /* when email expired or was successfully sent, queue is removed by postfix which leads to following log entry */
  val removedQueueRegex = """.*?: ([a-zA-Z0-9]{15}): removed$""".r
  /* message-id log entry has a value generated by mail client, it is available in LogEntry for future use */
  val midRegex = """.*?: ([a-zA-Z0-9]{15}): message-id=(.*?)$""".r
  /* client-id log entry is generated by postfix because it is set up to do so if agent client's (mail module) request header contains client-id
   * It allows for partitioning Index by clients so that we can run multiple newsletter campaigns at the same time */
  val cidRegex = """.*?: ([a-zA-Z0-9]{15}): info: header client-id: (.*?) .*$""".r
  /* Log entry of this form follows mail that was either deferred or sent successfully and it is to be removed from queue */
  val deliveryAttempt = """^(\d{4} [a-zA-Z]+ \d{1,2} \d{2}:\d{2}:\d{2}.\d{3}).*?([a-zA-Z0-9]{15}): to=<(.*?@.*?)>.*?status=(sent|deferred|bounced) \((.*)\)$""".r
  /* Log entry of this form follows mail that is considered expired and it is to be removed from queue */
  val expiredRegex = """^(\d{4} [a-zA-Z]+ \d{1,2} \d{2}:\d{2}:\d{2}.\d{3}).*?([a-zA-Z0-9]{15}): from=<(.*?@.*?)>.*?status=(expired), (.*)$""".r


  /**
   * Decide on type of bounce, soft bounce means that message was deferred and it is to be tried again later on. Hard bounce
   * means that the reason of not delivering a message was too serious to try to deliver message again, it is removed from queue right away
   *
   * @return tuple (hard-1/soft-0, bounce reason message)
   */
  def resolveState(info: String, status: String, hasBeenDeferred: Boolean, bounceMap: ListMap[String, ListMap[String, Regex]]): (Int, String) =
    status match {
      case "sent" if hasBeenDeferred => (4, "finally OK")
      case "sent" => (3, "OK")
      case "expired" => (0, "expired, returned to sender")
      case _ =>
        bounceMap.foldLeft((2, "unable to decide on type of bounce")) {
          case (acc, (bounceType, regexByCategory)) =>
            regexByCategory.find { case (category, regex) => regex.pattern.matcher(info).find() } match {
              case Some((category, regex)) => if (bounceType == "soft") (0, category) else (1, category)
              case None => acc
            }
        }
    }

  case class Arbiter(remainingSize: Double, toIndex: TreeSet[File], toIgnore: TreeSet[File]) {

    def toIndexInit: TreeSet[File] = if (toIndex.isEmpty) TreeSet[File]() else toIndex.init

  }

  def splitFiles(d: File, o: Options): Arbiter = {
    val rotatedPatternRegex = o.rotatedPattern.r

    def toMB(value: Double, places: Int): Double = {
      new BigDecimal(value / 1024D / 1024D).setScale(places, BigDecimal.ROUND_HALF_UP).doubleValue
    }

    def fileOrderName(f: File): Option[Int] = {
      rotatedPatternRegex
        .findFirstMatchIn(f.getName)
        .flatMap(m => if (m.groupCount > 0) Some(m.group(1)).map(_.toInt) else None)
    }

    def fileLength(f: File): Long = {
      if (f.getName.endsWith("gz") || f.getName.endsWith("tar")) f.length() * 10 else f.length()
    }

    if (new File(o.logDir + o.tailedLogFileName).createNewFile()) {
      log.warn(s"File to be tailed ${o.tailedLogFileName} doesn't exist, it was created...")
    }

    val allFiles = d.listFiles().filter(!_.isDirectory)
    val tailedFileSize = allFiles.find(_.getName == o.tailedLogFileName).get.length()
    val fileSizeToIndex = o.maxFileSizeToIndex * 1024 * 1024 - tailedFileSize

    log.info(f"Postfix is currently logging to file '${o.tailedLogFileName}' having size : ${toMB(tailedFileSize, 7)}%f MB")
    log.info(f"There is ${toMB(fileSizeToIndex, 7)}%f MB available for file indexing")
    log.info(s"Processing directory ${d.getCanonicalPath} searching for backup files :")

    val ordering = Ordering.by(fileOrderName)

    allFiles.
      filter( // filter out files that don't match pattern of rotated log files
        f => o.rotatedPatternFn(f.getName)
      ).
      sorted( // order matters, this deals with the fact that mail.log.12.gz file would incorrectly precede file mail.log.9.gz even though it's older
        ordering
      ).
      foldLeft( Arbiter(fileSizeToIndex, TreeSet[File]()(ordering.reverse), TreeSet[File]()(ordering.reverse)) ) {
      (arbiter, f) => {
        if (f.length() < 10) {
          log.warn(s"Skipping file ${f.getName} because it's empty !")
          Arbiter(arbiter.remainingSize, arbiter.toIndex, arbiter.toIgnore + f)
        } else if (arbiter.remainingSize < 0) {
          Arbiter(arbiter.remainingSize, arbiter.toIndex, arbiter.toIgnore + f)
        } else {
          val fileSize = fileLength(f)
          val remaining = arbiter.remainingSize - fileSize
          if (remaining > 0) {
            log.info(f"\t file ${f.getName} of size ${toMB(fileSize, 7)}%f MB is to be indexed, ${toMB(remaining, 7)}%f MB remains" )
            Arbiter(remaining, arbiter.toIndex + f, arbiter.toIgnore)
          } else {
            log.warn(f"\t file ${f.getName} of size ${toMB(fileSize, 7)}%f MB is to be persisted, limit ${o.maxFileSizeToIndex}%f MB was reached, ${toMB(remaining * -1, 7)}%f MB is missing for it to be indexed")
            Arbiter(remaining, arbiter.toIndex, arbiter.toIgnore + f)
          }
        }
      }
    }
  }

}
