package spark

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce.InputFormat
import org.apache.hadoop.mapreduce.InputSplit
import org.apache.hadoop.mapreduce.task.JobContextImpl
import org.apache.hadoop.mapreduce.JobID
import org.apache.hadoop.mapreduce.RecordReader
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.hadoop.mapreduce.TaskAttemptID
import org.apache.hadoop.mapreduce.TaskType

import java.util.Date
import java.text.SimpleDateFormat

class NewHadoopSplit(rddId: Int, val index: Int, @transient rawSplit: InputSplit with Writable)
  extends Split {
  
  val serializableHadoopSplit = new SerializableWritable(rawSplit)

  override def hashCode(): Int = (41 * (41 + rddId) + index).toInt
}

class NewHadoopRDD[K, V](
    sc: SparkContext,
    inputFormatClass: Class[_ <: InputFormat[K, V]],
    keyClass: Class[K], valueClass: Class[V],
    @transient conf: Configuration)
  extends RDD[(K, V)](sc) {
  
  private val serializableConf = new SerializableWritable(conf)

  private val jobtrackerId: String = {
    val formatter = new SimpleDateFormat("yyyyMMddHHmm")
    formatter.format(new Date())
  }

  @transient
  private val jobId = new JobID(jobtrackerId, id)

  @transient
  private val splits_ : Array[Split] = {
    val inputFormat = inputFormatClass.newInstance
    val jobContext = new JobContextImpl(serializableConf.value, jobId)
    val rawSplits = inputFormat.getSplits(jobContext).toArray
    val result = new Array[Split](rawSplits.size)
    for (i <- 0 until rawSplits.size) {
      result(i) = new NewHadoopSplit(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])
    }
    result
  }

  override def splits = splits_

  override def compute(theSplit: Split) = new Iterator[(K, V)] {
    val split = theSplit.asInstanceOf[NewHadoopSplit]
    val conf = serializableConf.value
    val attemptId = new TaskAttemptID(jobtrackerId, id, TaskType.MAP, split.index, 0)
    val context = new TaskAttemptContextImpl(serializableConf.value, attemptId)
    val format = inputFormatClass.newInstance
    val reader = format.createRecordReader(split.serializableHadoopSplit.value, context)
    reader.initialize(split.serializableHadoopSplit.value, context)
   
    var havePair = false
    var finished = false

    override def hasNext: Boolean = {
      if (!finished && !havePair) {
        finished = !reader.nextKeyValue
        havePair = !finished
        if (finished) {
          reader.close
        }
      }
      !finished
    }

    override def next: (K, V) = {
      if (!hasNext) {
        throw new java.util.NoSuchElementException("End of stream")
      }
      havePair = false
      return (reader.getCurrentKey, reader.getCurrentValue)
    }
  }

  override def preferredLocations(split: Split) = {
    val theSplit = split.asInstanceOf[NewHadoopSplit]
    theSplit.serializableHadoopSplit.value.getLocations.filter(_ != "localhost")
  }

  override val dependencies: List[Dependency[_]] = Nil
}
