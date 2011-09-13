package riff.raff.lib

import xml.NodeSeq
import net.liftweb.common.{Full, Box}

import _root_.org.openid4java.message.AuthRequest
import net.liftweb.openid._
import org.openid4java.discovery.{Identifier, DiscoveryInformation}
import org.openid4java.consumer.VerificationResult
import net.liftweb.http.S
import riff.raff.model.{AdminUserDetails, AdminUser}

trait GoogleOpenIDVendor extends SimpleOpenIDVendor {

  def ext(di: DiscoveryInformation, authReq: AuthRequest): Unit = {
    import WellKnownAttributes._
    WellKnownEndpoints.findEndpoint(di) map {ep =>
      ep.makeAttributeExtension(List(Email, FullName, FirstName, LastName)) foreach {ex =>
        authReq.addExtension(ex)
      }
    }
  }
  override def createAConsumer = new OpenIDConsumer[UserType] {
    beforeAuth = Full(ext _)
  }

  override def postLogin(id: Box[Identifier],res: VerificationResult): Unit = {
    val attrs = WellKnownAttributes.attributeValues(res.getAuthResponse)
    val email = attrs.get(WellKnownAttributes.Email).getOrElse("unknown email")
    val firstname = attrs.get(WellKnownAttributes.FirstName).getOrElse("Unknown")
    val lastname = attrs.get(WellKnownAttributes.LastName).getOrElse("Incognito")

    AdminUser(Full(AdminUserDetails(firstname, lastname, email)))

    id match {
      case Full(id) => S.notice(generateWelcomeMessage(id))

      case _ => S.error(generateAuthenticationFailure(res))
    }

  }


  override def logUserOut() {
    AdminUser.remove()
  }

  override protected def generateWelcomeMessage(id: Identifier): String = "Welcome : "+AdminUser.open_!.display
}

object GoogleOpenIDVendor extends GoogleOpenIDVendor


class OpenID {
  def renderForm(xhtml: NodeSeq): NodeSeq =
    <form method="post" action={"/"+GoogleOpenIDVendor.PathRoot+"/"+GoogleOpenIDVendor.LoginPath}>
      <input
        type="hidden"
        class="openidfield"
        name={GoogleOpenIDVendor.PostParamName}
        value="https://www.google.com/accounts/o8/id"/>
        <input type="submit" class="btn primary" value="Log In"/>
    </form>

}