package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"os"
	"os/exec"
	"strings"

	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/ec2"
	"github.com/payfazz/go-errors"
	"github.com/payfazz/go-errors/errhandler"
	"github.com/payfazz/mainutil"
)

func main() {
	defer errhandler.With(mainutil.ErrorHandler)

	var raw json.RawMessage
	errhandler.Check(json.NewDecoder(os.Stdin).Decode(&raw))

	var action action
	errhandler.Check(json.Unmarshal(raw, &action))

	switch action.Action {
	case "prepare":
		var prepare prepare
		errhandler.Check(json.Unmarshal(raw, &prepare))
		doPrepare(prepare)

	case "spawn":
		var spawn spawn
		errhandler.Check(json.Unmarshal(raw, &spawn))
		doSpawn(spawn)

	case "remove_hint":
		var removeHint removeHint
		errhandler.Check(json.Unmarshal(raw, &removeHint))
		doRemoveHint(removeHint)

	default:
		errhandler.Fail(errors.Errorf("unknown action: %s", action.Action))

	}
}

func doPrepare(prepare prepare) {
	// TODO: calculate instance type from spotinstance price

	if prepare.Nodes == nil {
		prepare.Nodes = make([]nodeInfo, 0, prepare.NumExecutor)
	}

	for i := 0; i < prepare.NumExecutor; i++ {
		prepare.Nodes = append(prepare.Nodes, nodeInfo{
			InstanceType: "t3a.nano",
			NumExecutor:  1,
		})
	}

	errhandler.Check(json.NewEncoder(os.Stdout).Encode(prepare))
}

func doSpawn(spawn spawn) {
	userDataYaml := strings.ReplaceAll(ec2UserData, "NODE_NAME_PLACEHOLDER_85fe867240ecd591b79034a07b0a8a1b", spawn.Node.Name)
	userDataYaml = strings.ReplaceAll(userDataYaml, "SECRET_PLACEHOLDER_85fe867240ecd591b79034a07b0a8a1b", spawn.Node.JnlpMac)

	var userDataBuffer bytes.Buffer
	coreOsCt := exec.Command("./coreos-ct")
	coreOsCt.Stdin = bytes.NewReader([]byte(userDataYaml))
	coreOsCt.Stdout = &userDataBuffer
	errhandler.Check(coreOsCt.Run())

	ec2Svc := getEC2Service()
	_, err := ec2Svc.RunInstances(&ec2.RunInstancesInput{
		MinCount:                          aws.Int64(1),
		MaxCount:                          aws.Int64(1),
		ImageId:                           aws.String("ami-03b2848db9a1e8331"), // TODO: retrieve this from CoreOS stable RSS
		InstanceType:                      aws.String(spawn.Node.InstanceType),
		KeyName:                           aws.String("00-win-pc"),
		SubnetId:                          aws.String("subnet-0fc952c11ca926480"),
		SecurityGroupIds:                  []*string{aws.String("sg-080adba2fd2b86505")},
		InstanceInitiatedShutdownBehavior: aws.String("terminate"),
		UserData:                          aws.String(base64.StdEncoding.EncodeToString(userDataBuffer.Bytes())),
		TagSpecifications: []*ec2.TagSpecification{
			{
				ResourceType: aws.String("instance"),
				Tags: []*ec2.Tag{
					{
						Key:   aws.String("JENKINS_NODE_NAME"),
						Value: aws.String(spawn.Node.Name),
					},
				},
			},
		},
	})
	errhandler.Check(err)

	spawn.Spawned = true
	errhandler.Check(json.NewEncoder(os.Stdout).Encode(spawn))
}

func doRemoveHint(removeHint removeHint) {
	ec2Svc := getEC2Service()
	if removeHint.NodeName == "" {
		return
	}

	result, err := ec2Svc.DescribeInstances(&ec2.DescribeInstancesInput{
		Filters: []*ec2.Filter{
			&ec2.Filter{
				Name:   aws.String("tag:JENKINS_NODE_NAME"),
				Values: []*string{aws.String(removeHint.NodeName)},
			},
		},
	})
	errhandler.Check(err)

	for _, v := range result.Reservations {
		var ids []*string
		for _, i := range v.Instances {
			ids = append(ids, i.InstanceId)
		}
		if len(ids) > 0 {
			_, err := ec2Svc.TerminateInstances(&ec2.TerminateInstancesInput{
				InstanceIds: ids,
			})

			errhandler.Check(err)
		}
	}
}

func getEC2Service() *ec2.EC2 {
	sess, err := session.NewSession(&aws.Config{
		Region: aws.String("ap-southeast-1"),
	})
	errhandler.Check(err)
	return ec2.New(sess)
}
