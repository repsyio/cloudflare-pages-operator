# Image used by the deploy Jobs to upload a directory to Cloudflare Pages.
# Debian based (not alpine) because wrangler's optional native dependencies expect glibc.
FROM node:22-bookworm-slim

ARG WRANGLER_VERSION=4.131.1
RUN npm install -g "wrangler@${WRANGLER_VERSION}" \
 && npm cache clean --force

ENV WRANGLER_SEND_METRICS=false \
    CI=true
USER node
WORKDIR /home/node
ENTRYPOINT ["wrangler"]
