package ca.int13.librenmsmcp.auth;

import ca.int13.librenmsmcp.authz.Principal;

/** The outcome of one authenticator looking at a request's credentials. */
public sealed interface AuthResult {
    /** The credentials are valid. */
    record Authenticated(Principal principal) implements AuthResult {}

    /** Not this authenticator's kind of credential; the next one may accept it. */
    record NotApplicable() implements AuthResult {}

    /**
     * The credentials were recognized and refused. {@code reason} is for server logs only; clients
     * see just the RFC 6750 error code.
     */
    record Rejected(Error error, String reason) implements AuthResult {}

    /** RFC 6750 error codes, with the HTTP status each implies. */
    enum Error {
        INVALID_TOKEN("invalid_token", 401),
        INSUFFICIENT_SCOPE("insufficient_scope", 403);

        public final String code;
        public final int status;

        Error(String code, int status) {
            this.code = code;
            this.status = status;
        }
    }

    AuthResult NOT_APPLICABLE = new NotApplicable();
}
