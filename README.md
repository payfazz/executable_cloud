# Executable cloud

This is jenkins plugin.

A plugin that extend Cloud, you can provide any arbitary executable file as `Cloud` instance in jenkins.

That executable file will be called when node is needed or removed

Every node that idle or disconnect for 10 mintues will be automatically removed from jenkins node list.

## Comunication protocol

Every comunication to executable file is provided via stdin and stdout of that executable program.

There are 3 action:
- `prepare`, this is called when jenkins need more node, executable program must response a json string that include information on how node will be crated
- `spawn`, this is called when jenkins need to spawn the node
- `remove_hint`, this is called when jenkins remove the node from node list

### `prepare`

example input:
```json
{"action":"prepare","num_executor":3}
```

example output:
```json
{"action":"prepare","num_executor":3,"nodes":[{"num_executor":2},{"num_executor":2}]}
```

### `spawn`

for every node in `nodes` that returned by `prepare` will be passed to `spawn`.

example input:
```json
{"action":"spawn","node":{"num_executor":2,"name":"test-randomhere","jnlp_mac":"xxxyyyzzz"}}
```

example output:
```json
{"action":"spawn","node":{"num_executor":2,"name":"test-randomhere","jnlp_mac":"xxxyyyzzz"},"spawned":true}
```

### `remove_hint`

when node removed from jenkins node list, this action will be called

example input:
```json
{"action":"remove_hint","node_name":"test-randomhere"}
```

# TODO

- more documentation
