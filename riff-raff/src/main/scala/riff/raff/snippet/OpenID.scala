package riff.raff.snippet

import xml.NodeSeq
import net.liftweb.openid.SimpleOpenIDVendor

class OpenID {
  def renderForm(xhtml: NodeSeq): NodeSeq =
    <form method="post" action={"/"+SimpleOpenIDVendor.PathRoot+"/"+SimpleOpenIDVendor.LoginPath}>
      <input
        type="hidden"
        class="openidfield"
        name={SimpleOpenIDVendor.PostParamName}
        value="https://www.google.com/accounts/o8/id"/>
        <input type="submit" class="btn primary" value="Log In"/>
    </form>

}