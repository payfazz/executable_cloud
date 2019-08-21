package main

const ec2UserData = `---
storage:
  files:
  - filesystem: root
    path: /opt/bootstrap_script/bootstrap
    mode: 0755
    contents:
      inline: |
        #!/bin/sh

        set -eux
        cd /

        if ! [ "$(id -u)" = 0 ]; then
          echo "not running as root"
          exit 1
        fi

        if [ -f /.bootstraped ]; then
          echo "already bootstraped"
          exit
        fi
        date +%s > /.bootstraped

        (
        # swap
        dd if=/dev/zero of=/swap bs=1MB count=1024
        chmod 600 /swap
        mkswap /swap
        echo '/swap swap swap defaults 0 0' >> /etc/fstab
        ) &
        mkswappid=$!

        (
        # sshd config
        rm -rf /etc/ssh/sshd_config
        cp /usr/share/ssh/sshd_config /etc/ssh/sshd_config
        cat <<'EOF' >> /etc/ssh/sshd_config

        PermitRootLogin yes
        AuthenticationMethods publickey
        EOF

        # ssh pubkey
        mkdir -p /root/.ssh
        chmod 700 /root/.ssh
        echo "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKvsZgBa1/cu5XxzbsNyAsKkhC9ILF1UINA03Y+TuhKD kurnia.d.win@gmail.com" >> /root/.ssh/authorized_keys
        chmod 600 /root/.ssh/authorized_keys
        )

        (
        # daemon config
        mkdir -p /etc/docker
        cat <<'EOF' > /etc/docker/daemon.json
        {
          "exec-opts":[
            "native.cgroupdriver=systemd"
          ],
          "log-driver": "json-file",
          "log-opts": {
            "max-size": "50m",
            "max-file": "5"
          },
          "storage-driver": "overlay2"
        }
        EOF

        # payfazz docker convention
        systemctl stop docker.service docker.socket
        mkdir -p /container_state
        chmod 711 /container_state
        mkdir -p /var/lib/docker
        mv /var/lib/docker /container_state/.docker
        ln -s /container_state/.docker /var/lib/docker
        systemctl enable docker.service docker.socket

        # docker-sh
        curl -sSLf https://raw.githubusercontent.com/payfazz/docker-sh/master/install.sh | sh -s - /opt/bin/docker.sh
        )

        wait $mkswappid
        reboot
        exit

  - filesystem: root
    path: /container_state/jenkins_slave/app
    mode: 0755
    contents:
      inline: |
        #!/usr/bin/env docker.sh

        image=wint/jenkins_slave
        must_local=y
        opts="
          --restart always

          --env-file '$dir/env'

          -v '$dir/data:/jenkins'
          -v /var/run/docker.sock:/var/run/docker.sock

          --log-driver json-file
          --log-opt max-size=50m
          --log-opt max-file=3
        "

  - filesystem: root
    path: /container_state/jenkins_slave/env
    mode: 0644
    contents:
      inline: |
        JENKINS_URL=https://demo-jenkins.win.payfazz.com/
        NODE_NAME=NODE_NAME_PLACEHOLDER_85fe867240ecd591b79034a07b0a8a1b
        SECRET=SECRET_PLACEHOLDER_85fe867240ecd591b79034a07b0a8a1b

systemd:
  units:
  - name: bootstrap.service
    enabled: true
    contents: |
      [Service]
      ExecStart=/opt/bootstrap_script/bootstrap
      [Install]
      WantedBy=multi-user.target

  - name: docker.service
    dropins:
    - name: bootstrap.conf
      contents: |
        [Unit]
        Requires=bootstrap.service
        After=bootstrap.service

  - name: jenkins-slave-starter.service
    enabled: true
    contents: |
      [Unit]
      Requires=bootstrap.service docker.service
      After=bootstrap.service docker.service
      [Service]
      ExecStart=/bin/sh -ceux 'PATH=/opt/bin:$PATH && /container_state/jenkins_slave/app start'
      [Install]
      WantedBy=multi-user.target
`
