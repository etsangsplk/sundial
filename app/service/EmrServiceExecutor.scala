package service

import java.util.{Date, UUID}

import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduceClient
import com.amazonaws.services.elasticmapreduce.model._
import dao.{ExecutableStateDao, SundialDao}
import model.{EmrExecutable, EmrState, Task, TaskExecutorStatus}

import scala.collection.JavaConverters._

class EmrServiceExecutor extends SpecificTaskExecutor[EmrExecutable, EmrState]{

  lazy val emrClient = new AmazonElasticMapReduceClient()

  private lazy val config = play.Play.application.configuration

  private lazy val emrCluster = config.getString("emr.cluster")

  override def stateDao(implicit dao: SundialDao): ExecutableStateDao[EmrState] = dao.emrStateDao

  override protected def actuallyStartExecutable(executable: EmrExecutable, task: Task)(implicit dao: SundialDao): EmrState = {
    val addJobFlowSteps = new AddJobFlowStepsRequest()
    val hadoopJarStep = new HadoopJarStepConfig()
    hadoopJarStep.setArgs(executable.args.asJava)
    hadoopJarStep.setJar(executable.jar)
    hadoopJarStep.setMainClass(executable.mainClass)
    hadoopJarStep.setProperties(executable.properties.map(property => new KeyValue(property._1, property._2)).asJavaCollection)

    val stepConfig = new StepConfig()
    stepConfig.setName(executable.name)
    stepConfig.setHadoopJarStep(hadoopJarStep)
    addJobFlowSteps.setSteps(Seq(stepConfig).asJava)
    addJobFlowSteps.setJobFlowId(emrCluster)
    val addJobFlowResult = emrClient.addJobFlowSteps(addJobFlowSteps)
    EmrState(UUID.randomUUID(), new Date(), addJobFlowResult.getStepIds.get(0), TaskExecutorStatus.Initializing)
  }

  override protected def actuallyKillExecutable(state: EmrState, task: Task)(implicit dao: SundialDao): Unit = {
  }

  override protected def actuallyRefreshState(state: EmrState)(implicit dao: SundialDao): EmrState = {
    val describeStepRequest = new DescribeStepRequest()
    describeStepRequest.setClusterId(emrCluster)
    describeStepRequest.setStepId(state.emrStepId)
    val results = emrClient.describeStep(describeStepRequest)
    val emrStatus = StepState.fromValue(results.getStep.getStatus.getState)
    val stateChangeReason = Option(results.getStep.getStatus.getStateChangeReason.getMessage)
    val status: TaskExecutorStatus = emrStatus match {
      case StepState.PENDING => TaskExecutorStatus.Initializing
      case StepState.RUNNING => TaskExecutorStatus.Running
      case StepState.COMPLETED => TaskExecutorStatus.Completed
      case StepState.CANCELLED => TaskExecutorStatus.Fault(stateChangeReason)
      case StepState.FAILED => TaskExecutorStatus.Fault(stateChangeReason)
      case StepState.INTERRUPTED => TaskExecutorStatus.Fault(stateChangeReason)
    }
    EmrState(state.taskId, new Date(), state.emrStepId, status)
  }
}
