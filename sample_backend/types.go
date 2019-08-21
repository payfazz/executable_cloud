package main

type action struct {
	Action string `json:"action,omitempty"`
}

type prepare struct {
	action
	NumExecutor int        `json:"num_executor,omitempty"`
	Nodes       []nodeInfo `json:"nodes,omitempty"`
}

type spawn struct {
	action
	Node    nodeInfo `json:"node,omitempty"`
	Spawned bool     `json:"spawned,omitempty"`
}

type removeHint struct {
	action
	NodeName string `json:"node_name,omitempty"`
}

type nodeInfo struct {
	Name         string `json:"name,omitempty"`
	NumExecutor  int    `json:"num_executor,omitempty"`
	JnlpMac      string `json:"jnlp_mac,omitempty"`
	InstanceType string `json:"ec2_instance_type,omitempty"`
}
