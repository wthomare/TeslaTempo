# Official Tesla source pinned by Git revision and archive checksum.
FROM golang:1.27.2 AS build
WORKDIR /src
ADD --checksum=sha256:d82b187efaba5707d7d8049da0d411d46590f35edcff7b06dc4cc405456d8e67 https://codeload.github.com/teslamotors/vehicle-command/tar.gz/d6239bf377b8a5859e8f5c9d64409df2a057934a /tmp/tesla.tar.gz
RUN tar -xzf /tmp/tesla.tar.gz --strip-components=1 -C /src \
    && go mod download \
    && CGO_ENABLED=0 go build -trimpath -o /out/tesla-http-proxy ./cmd/tesla-http-proxy
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tesla-http-proxy /usr/local/bin/tesla-http-proxy
COPY --from=build /src/LICENSE /usr/share/licenses/tesla-vehicle-command/LICENSE
ENTRYPOINT ["/usr/local/bin/tesla-http-proxy"]
