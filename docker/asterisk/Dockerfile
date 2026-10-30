# Minimal Asterisk (PJSIP) for a gsm2sip gateway and a few SIP clients.
# See docs/asterisk-docker.md.
FROM alpine:3.20
RUN apk add --no-cache asterisk asterisk-sounds-en
COPY conf/ /etc/asterisk/
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 755 /usr/local/bin/entrypoint.sh && mkdir -p /var/lib/asterisk-db
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
