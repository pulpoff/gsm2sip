/* SIP message parsing and building — a port of app/.../sip/SipMessage.kt
 * (SipMessage + SipBuilder) and SipAuth.kt.  The builders produce the same
 * bytes the Android gateway puts on the wire, header for header, because
 * that is what the server's dialplan has been written against. */
#pragma once
#include <glib.h>

typedef struct {
	char *start_line;
	GHashTable *headers;	/* lowercase name -> value; a repeated header keeps the LAST value, as the Kotlin map did */
	char *body;
} SipMsg;

SipMsg *sipmsg_parse(const char *data, gsize len);
void sipmsg_free(SipMsg *m);
G_DEFINE_AUTOPTR_CLEANUP_FUNC(SipMsg, sipmsg_free)

const char *sipmsg_header(const SipMsg *m, const char *lname);
gboolean sipmsg_is_request(const SipMsg *m);
int sipmsg_status(const SipMsg *m);		/* 0 for a request */
char *sipmsg_method(const SipMsg *m);		/* NULL for a response */
char *sipmsg_request_uri(const SipMsg *m);
int sipmsg_cseq_num(const SipMsg *m);		/* -1 if absent */
gboolean sipmsg_cseq_is(const SipMsg *m, const char *method);

char *sip_extract_user(const char *value);	/* user part of the first sip: URI */
char *sip_extract_tag(const char *value);
char *sipmsg_from_tag(const SipMsg *m);
char *sipmsg_to_tag(const SipMsg *m);
char *sipmsg_caller_number(const SipMsg *m);	/* P-Asserted-Identity, Remote-Party-ID, then From */
char *sipmsg_caller_display(const SipMsg *m);
char *sipmsg_contact_uri(const SipMsg *m);
gboolean sipmsg_contact_addr(const SipMsg *m, char **host, int *port);

/* SDP */
int sipmsg_sdp_port(const SipMsg *m);		/* -1 if none */
char *sipmsg_sdp_addr(const SipMsg *m);
int sipmsg_sdp_preferred_pt(const SipMsg *m, const char *codec_mode);
int sipmsg_sdp_te_pt(const SipMsg *m);		/* -1 if none */
gboolean sipmsg_sdp_is_savp(const SipMsg *m);

typedef struct { int tag; char *suite; char *inline_key; } SdpCrypto;
GPtrArray *sipmsg_sdp_crypto(const SipMsg *m);	/* of SdpCrypto*, owned */

/* Digest auth (RFC 2617, no qop — what chan_sip/pjsip ask of this client) */
typedef struct { char *realm, *nonce, *opaque, *qop; } SipAuthParams;
SipAuthParams *sip_auth_parse(const SipMsg *m);
void sip_auth_free(SipAuthParams *p);
char *sip_auth_header(const char *method, const char *uri, const char *user,
		      const char *pass, const SipAuthParams *p);

/* Builders.  Every one of these returns a complete message, CRLF-terminated,
 * that the caller owns.  `via_transport` is "UDP" or "TLS". */
typedef struct {
	const char *user_agent;
	const char *transport;		/* "UDP" | "TLS" */
	const char *codec_mode;		/* "g722" | "g711" | "both" */
} SipBuildCtx;

typedef struct {
	const char *suite;		/* SDP name */
	const char *inline_key;		/* base64 key||salt */
	int tag;
} SdpSrtp;

char *sip_branch(void);
char *sip_tag(void);
char *sip_build_sdp(const SipBuildCtx *c, const char *ip, int rtp_port, const SdpSrtp *srtp);
char *sip_build_register(const SipBuildCtx *c, const char *user, const char *domain, int server_port,
			 const char *ip, int port, const char *call_id, int cseq, const char *auth);
char *sip_build_invite(const SipBuildCtx *c, const char *target_uri, const char *user, const char *domain,
		       const char *ip, int port, const char *call_id, int cseq, int rtp_port,
		       const char *from_tag, const char *cid_number, const char *cid_name,
		       const char *auth, const SdpSrtp *srtp);
char *sip_build_message(const SipBuildCtx *c, const char *target_uri, const char *from_user,
			const char *domain, const char *ip, int port, const char *call_id, int cseq,
			const char *body, const char *content_type, char **extra_headers,
			const char *from_tag, const char *auth);
char *sip_build_status(const SipBuildCtx *c, const SipMsg *req, int code, const char *reason,
		       char **extra_headers, const char *to_tag);
char *sip_build_ok(const SipBuildCtx *c, const SipMsg *req, const char *user, const char *ip, int port,
		   int rtp_port, const char *to_tag, const SdpSrtp *srtp);
char *sip_build_trying(const SipBuildCtx *c, const SipMsg *req);
char *sip_build_ringing(const SipBuildCtx *c, const SipMsg *req, const char *to_tag);
char *sip_build_reject(const SipBuildCtx *c, const SipMsg *req, int code, const char *reason, const char *to_tag);
char *sip_build_ack(const SipBuildCtx *c, const char *target_uri, const char *to, const char *from,
		    const char *call_id, int cseq, const char *user, const char *ip, int port);
/* ACK for a final non-2xx answer to our INVITE.  RFC 3261 §17.1.1.3: it is
 * part of the INVITE transaction, so it carries that INVITE's Via (which the
 * response echoes) and Request-URI -- a fresh branch reads as a stray ACK, and
 * the server keeps retransmitting the 401/486 until its Timer H. */
char *sip_build_ack_for_error(const SipBuildCtx *c, const char *request_uri, const SipMsg *resp);
char *sip_build_bye(const SipBuildCtx *c, const char *target_uri, const char *from, const char *to,
		    const char *call_id, int cseq, const char *user, const char *ip, int port);
char *sip_build_cancel(const SipBuildCtx *c, const char *target_uri, const char *via, const char *from,
		       const char *to, const char *call_id, int cseq);
char *sip_build_options_ok(const SipBuildCtx *c, const SipMsg *req, const char *user, const char *ip, int port);
