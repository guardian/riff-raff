package test

import com.gu.googleauth.UserIdentity
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import controllers.{AuthorisationValidator, AuthorisationRecord}
import org.joda.time.DateTime

class AuthenticationTest extends AnyFlatSpec with Matchers {
  "AuthorisationValidator" should "allow any domain when not configured" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = Nil
      def emailAllowListEnabled = false
      def emailAllowListContains(email: String) = false
    }
    val id = UserIdentity("","test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "allow configured allowlist domains" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = List("guardian.co.uk")
      def emailAllowListEnabled = false
      def emailAllowListContains(email: String) = false
    }
    val id = UserIdentity("","test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "disallow domains not configured for allowlisting" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = List("guardian.co.uk")
      def emailAllowListEnabled = false
      def emailAllowListContains(email: String) = false
    }
    val id = UserIdentity("","test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be("The e-mail address domain you used to login to Riff-Raff (test@test.com) is not in the configured allowlist.  Please try again with another account or contact the Riff-Raff administrator.")
  }

  it should "allow a allowlist e-mail address" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = List("guardian.co.uk")
      def emailAllowListEnabled = true
      def emailAllowListContains(email: String) = email == "test@guardian.co.uk"
    }
    val id = UserIdentity("","test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(true)
  }

  it should "disallow a allowlist e-mail address in a non-allowlist domain" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = List("guardian.co.uk")
      def emailAllowListEnabled = true
      def emailAllowListContains(email: String) = email == "test@test.com"
    }
    val id = UserIdentity("","test@test.com", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be("The e-mail address domain you used to login to Riff-Raff (test@test.com) is not in the configured allowlist.  Please try again with another account or contact the Riff-Raff administrator.")
  }

  it should "disallow a non-allowlist e-mail address in a allowlist domain" in {
    val validator = new AuthorisationValidator {
      def emailDomainAllowList = List("guardian.co.uk")
      def emailAllowListEnabled = true
      def emailAllowListContains(email: String) = false
    }
    val id = UserIdentity("","test@guardian.co.uk", "Test", "Testing", 3600, None)
    validator.isAuthorised(id) should be(false)
    validator.authorisationError(id).get should be("The e-mail address you used to login to Riff-Raff (test@guardian.co.uk) is not authorised.  Please try again with another account, ask a colleague to add your address or contact the Riff-Raff administrator.")
  }

}
