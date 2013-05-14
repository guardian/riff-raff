package magenta.tasks

import java.io.File
import org.scalatest.mock.MockitoSugar
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FlatSpec
import org.mockito.Mockito._
import magenta.{ApiCredentials, FailException, KeyRing, Package}
import moschops.FastlyAPIClient
import com.ning.http.client.{ListenableFuture, FluentCaseInsensitiveStringsMap, Response}
import scala.io.Source
import com.gu.FastlyAPIClient

class FastlyTasksTest extends FlatSpec with ShouldMatchers with MockitoSugar {

  val pkgDir = mock[File]
  val pkg = mock[Package]
  val fastlyApiClient = mock[FastlyAPIClient]
  val fastlyApiClientBuilder = mock[FastlyApiClientBuilder]
  val keyRing = mock[KeyRing]
  val task = UpdateFastlyConfig(pkg, fastlyApiClientBuilder)

  val vclFile = new File(getClass.getResource("fastly.vcl").toString)

  val failResponse = mock[Response]
  when(failResponse.getStatusCode) thenReturn 400
  val headers = new FluentCaseInsensitiveStringsMap()
  headers.add("header1", "value1")
  headers.add("header2", "value2")
  when(failResponse.getHeaders) thenReturn headers
  when(failResponse.getResponseBody) thenReturn "{ failing response body }"
  val failResponseProxy = mock[ListenableFuture[Response]]
  when(failResponseProxy.get) thenReturn failResponse

  val succeedResponse = mock[Response]
  when(succeedResponse.getStatusCode) thenReturn 200
  when(succeedResponse.getResponseBody) thenReturn "{ successful response body }"

  behavior of "An UpdateFastlyConfig"

  it should "fail when no credentials available" in {
    when(keyRing.apiCredentials) thenReturn Nil

    try {
      task.execute(keyRing)
    } catch {
      case e: FailException => e.message should be("No Fastly credentials available")
    }
  }

  it should "fail when response from Fastly is not ok" in {
    when(keyRing.apiCredentials) thenReturn List(ApiCredentials("fastly", "serviceId", "apiKey"))
    when(pkg.srcDir) thenReturn pkgDir
    when(pkgDir.listFiles) thenReturn Array[File]()
    when(fastlyApiClientBuilder.build("apiKey", "serviceId")) thenReturn fastlyApiClient
    when(fastlyApiClient.latestVersionNumber()) thenReturn 1
    when(fastlyApiClient.versionClone(1)) thenReturn failResponseProxy

    try {
      task.execute(keyRing)
    } catch {
      case e: FailException =>
        e.message should be("Status code: 400; Headers: header1=[value1]; header2=[value2]; Response body: { failing response body }")
    }
  }

  // TODO
  //  it should "succeed when all responses from Fastly are ok" in {
  //    when(keyRing.apiCredentials) thenReturn List(ApiCredentials("fastly", "serviceId", "apiKey"))
  //    when(pkg.srcDir) thenReturn pkgDir
  //    when(pkgDir.listFiles) thenReturn Array(vclFile)
  //    when(fastlyApiClientBuilder.build("apiKey", "serviceId")) thenReturn fastlyApiClient
  //    when(fastlyApiClient.latestVersionNumber) thenReturn 1
  //    when(fastlyApiClient.versionClone(1)) thenReturn succeedResponse
  //    when(fastlyApiClient.vclUpdate(Map(), 1)) thenReturn List(succeedResponse)
  //
  //    task.execute(keyRing)
  //  }

}
