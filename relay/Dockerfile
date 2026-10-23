FROM node:22-alpine
ENV NODE_ENV=production PORT=8080 DATA_DIR=/data
WORKDIR /app
RUN mkdir /data && chown node:node /data
COPY --chown=node:node server.mjs package.json ./
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "server.mjs"]
