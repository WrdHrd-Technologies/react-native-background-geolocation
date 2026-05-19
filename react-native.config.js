module.exports = {
    dependency: {
        platforms: {
            android: {
                sourceDir: "./android",
                packageInstance: "new BackgroundGeolocationPackage()",
                packageImportPath: "import com.marianhello.bgloc.react.BackgroundGeolocationPackage;"
            }
        },
    }
};
