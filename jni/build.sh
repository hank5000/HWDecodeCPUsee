ndk-build
echo "Copy to jniLibs"
cp -rf ../libs/* ../app/src/main/jniLibs/
echo "Done!"
cp ../prebuild/libhack.so ../app/src/main/jniLibs/armeabi-v7a/
cp ../prebuild/libhack.so ../app/src/main/jniLibs/armeabi/
cp ../prebuild/libhack.so ../app/src/main/jniLibs/arm64-v8a/
