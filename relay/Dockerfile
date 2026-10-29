# Pinned multi-arch index digest (Node 26, bumped by Dependabot). Node 26 ships OpenSSL 3.5, which the ML-DSA authority needs.
FROM node:26-alpine@sha256:40cd011b55415bc894e45b02fdd5a5dfde056921b7d23a64c74f80497af48797
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data
WORKDIR /app
RUN mkdir /data && chown node:node /data
COPY --chown=node:node server.mjs package.json ./
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "server.mjs"]
