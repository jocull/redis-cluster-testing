const fs = require('fs');

const targetImage = 'redis:7.0.5';
const targetNodeCount = 9;
const targetPrimaryCount = 3;
const startPort = 6379;

const prelude =
`version: "3.9"
networks:
  default:
    name: redis-net
    external: true
services:`;
const sections = [prelude];

// Calculate nodes
const nodes = [];
for (let i = 0; i < targetNodeCount; i++) {
  nodes.push({
    hostname: 'redis' + (i + 1 + "").padStart(2, '0'),
    port: startPort,
  });
}

// Generate services
nodes.forEach(node => {
  sections.push(
`  ${node.hostname}:
    image: ${targetImage}
    command: redis-server --cluster-enabled yes --port ${node.port} --cluster-announce-hostname ${node.hostname} --cluster-preferred-endpoint-type hostname --cluster-node-timeout 5000`);
});

// Generate initialization script
const primaries = nodes.slice(0, targetPrimaryCount);
const replicas = nodes.slice(targetPrimaryCount);

sections.push(
`  activate:
    image: ${targetImage}
    depends_on:`);
nodes.forEach(node => {
  sections.push(
`      - ${node.hostname}`);
});

sections.push(
`    command: >
      bash -c "
      sleep 10`);

const primaryHostPorts = primaries.map(node => `${node.hostname}:${node.port}`);
const primarySet = primaryHostPorts.join(' ');
sections.push(
`      && redis-cli --cluster create ${primarySet} --cluster-yes`);

replicas.forEach(node => {
  sections.push(
`      && redis-cli --cluster add-node ${node.hostname}:${node.port} ${primaryHostPorts[0]} --cluster-slave --cluster-yes`);
});
sections[sections.length - 1] += `"`; // Close the last quote

sections.push(''); // Trailing line

fs.writeFileSync('docker-compose.yml', sections.join('\n'));
