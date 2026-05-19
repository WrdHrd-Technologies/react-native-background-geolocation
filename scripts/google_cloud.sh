#!/usr/bin/env bash
#
#  LICENSE ... (Keep your license header intact)
#

# Since we will download a video, we require integrity checking with CRC32c
# But the crcmod installation in the docker image isn't using the module's C extension
# So, uninstall it and install again with the C extension
echo "y" | sudo pip uninstall crcmod

sudo pip install -U crcmod

project_dir="${HOME}/mauron85_bgloc"

# create json key file
echo $GCLOUD_SERVICE_KEY | base64 --decode --ignore-garbage > ${HOME}/gcloud-service-key.json

# activate the account
gcloud auth activate-service-account --key-file ${HOME}/gcloud-service-key.json

# config the project
gcloud config set project ${GCLOUD_PROJECT}

# Run Instrumented test - PATH UPDATED TO REMOVE /lib
gcloud firebase test android run \
  --type instrumentation \
  --app ${project_dir}/res/dummy.apk \
  --test $(ls -dt ${project_dir}/android/build/outputs/apk/androidTest/debug/*.apk | head -1) \
  --device model=Nexus6,version=25,locale=en,orientation=portrait  \
  --timeout 90s