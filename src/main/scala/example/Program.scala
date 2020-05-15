package example

import java.time.LocalDate
import java.time.LocalDate._

import example.Impl._
import better.files._
import example.Model.JobResults

import scala.util.Try
import scala.util.chaining._
import Model.OptionPickler._
import com.typesafe.scalalogging.LazyLogging

/**
 * Main business logic.
 *
 * 1. Calculate resume pointer from object.bookmark file if it exists otherwise from beginning of time
 * 2. Break export into monthly chunks from bookmark until now
 * 3. Start AQUA export job for each chunk in sequence
 * 4. Keep checking if the job is done
 * 5. Download chunk to object-YYYY-MM-DD.csv
 * 6. Append lines from the chunk to aggregate file object.csv
 * 7. Once all chunks have downloaded write object.success file
 */
object Program extends LazyLogging {
  def exportObject(objectName: String, fields: List[String], beginningOfTime: String): String = {
//    if (objectName == "Account") boom(15000)
    if (file"$scratchDir/$objectName.success".exists) {
      s"$objectName full export has already successfully competed. Skipping!"
    } else {
      val bookmark = readBookmark(objectName, beginningOfTime) tap { bookmark => logger.info(s"Resume $objectName from $bookmark") }
      val chunkRange = fromBookmarkUntilNowByMonth(bookmark)
      val totalChunks = chunkRange.length
      chunkRange foreach { step =>
        val start = bookmark.plusMonths(step)
        val end = start.plusMonths(1)
        val chunk = s"(${step + 1}/$totalChunks)"
        val zoqlQuery = buildZoqlQuery(objectName, fields, start, end)
        val jobId = startAquaJob(zoqlQuery, objectName, start) tap { jobId => logger.info(s"Exporting $objectName $start to $end chunk $chunk by job $jobId") }
        val jobResult = getJobResult(jobId)
        val batch = jobResult.batches.head
        val csvContents = downloadCsvFile(batch)
        val lines = csvContents.linesIterator.toList tap( _ => logger.info(s"Completed converting downloaded $objectName content to lines"))
        val recordCountWithoutHeader = lines.length - 1
        assert(recordCountWithoutHeader == batch.recordCount, s"Downloaded record count should match $jobId metadata record count $recordCountWithoutHeader =/= ${batch.recordCount}; $lines")

        writeHeaderOnce(objectName, lines) tap (_ => logger.info(s"Completed $objectName header processing"))

        lines match {
          case Nil => throw new RuntimeException("Downloaded CSV file should have at least a header")

          case header :: Nil => // file is empty so just touch it
            Try(file"$scratchDir/$objectName-$start.csv".delete())
            file"$scratchDir/$objectName-$start.csv".touch()
            file"$scratchDir/$objectName-$start.metadata".write(write(jobResult))
            file"$scratchDir/$objectName.bookmark".write(end.toString)
            logger.info(s"$objectName $start - $end empty chunk $chunk done.")

          case header :: rows =>
            logger.info(s"Appending $objectName lines to .csv file")
            val csvWithDeletedColumn = rows.map(row => s"false,$row")
            Try(file"$scratchDir/$objectName-$start.csv".delete())
            file"$scratchDir/$objectName-$start.csv".printLines(csvWithDeletedColumn)
            file"$scratchDir/$objectName-$start.metadata".write(write(jobResult))
            file"$outputDir/$objectName.csv".printLines(csvWithDeletedColumn)
            file"$scratchDir/$objectName.bookmark".write(end.toString)
            logger.info(s"Done $objectName $start to $end chunk $chunk with record count $recordCountWithoutHeader exported by job $jobId")
        }
      }

      file"$scratchDir/$objectName.success".touch()
      s"All $objectName chunks exported!"
    }
  }
}
