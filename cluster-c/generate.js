const fs = require('fs');

const nodes = 9;
const startPort = 6400;

const prelude =
`version: "3.9"
networks:
  default:
    name: redis-net
    external: true
services:`;

const sections = [prelude];
for (let i = 0; i < nodes; i++) {
    const node = 'redis' + (i + 1 + "").padStart(2, '0');
    const port = startPort + i;
    const section =
`  ${node}:
    image: redis:7.0.5
    ports:
      - "${port}:${port}"
    command: redis-server --cluster-enabled yes --port ${port} --cluster-announce-hostname ${node} --cluster-preferred-endpoint-type hostname`;
    sections.push(section);
}

console.log(sections.join('\n'));