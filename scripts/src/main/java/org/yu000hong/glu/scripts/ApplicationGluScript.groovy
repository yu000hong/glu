package org.yu000hong.glu.scripts

import org.linkedin.glu.agent.api.ShellExecException

class ApplicationGluScript{
    def appDir
    def appFile
    def logsDir
    def logFile
    def pidFile

    // a method called by the rest of the code but not by the agent directly

    // why use closure vs method ? the rule is simple: if you are modifying any field (the ones
    // defined at the top of this file), then use a closure otherwise the update won't make it to
    // ZooKeeper.

    private Integer isProcessUp(){
        try{
            log.debug("pid file: ${pidFile.file.absolutePath}")
            if(pidFile.exists()){
                def pid = shell.exec("cat ${pidFile.file.absolutePath}")
                def cnt = shell.exec("ps -e -o pid|grep $pid|wc -l") as int
                if(cnt == 1){
                    return pid as int
                }else{
                    return null
                }
            }else{
                log.debug("pid file does not exist: ${pidFile.file.absolutePath}")
            }
        } catch(ShellExecException ignored){
            return null
        }
    }

    private boolean isProcessDown(){
        return isProcessUp() == null
    }

    private Integer isServerUp(){
        Integer newPid = isProcessUp()
        if(newPid && shell.listening('localhost', params.port)) {
            return newPid
        } else {
            return null
        }
    }


    /**
     * Defines the timer that will check for the server to be up and running and will act
     * according if not (change state)
     */
    def serverMonitor = {
        try {
            def up = isServerUp()

            def currentState = stateManager.state.currentState
            def currentError = stateManager.state.error

            def newState = null
            def newError = null

            // case when current state is running
            if(currentState == 'running'){
                if(!up) {
                    newState = 'stopped'
                    newError = 'Server down detected. Check the log file for errors.'
                    log.warn "${newError} => forcing new state ${newState}"
                } else{
                    newState = 'running' // remain running
                    log.info "App is up, clearing error status."
                }
            } else{
                if(up){
                    newState = 'running'
                    log.info "Server up detected."
                }
            }

            if(newState) {
                stateManager.forceChangeState(newState, newError)
            }

            log.debug "Server Monitor: ${stateManager.state.currentState} / ${up}"
        } catch(Throwable th){
            log.warn "Exception while running serverMonitor: ${th.message}"
            log.debug("Exception while running serverMonitor (ignored)", th)
        }
    }


    def install = {
        log.info "Installing..."

        def app = shell.fetch(params.app)
        def distribution = shell.untar(app)
        shell.rmdirs(mountPoint)
        appDir = shell.mv(shell.ls(distribution)[0], mountPoint)
        logsDir = appDir.'logs'
        shell.mkdirs(logsDir)

        appFile = appDir.bin.app
        pidFile = appDir.'pid.txt'
        logFile = logsDir.'log.txt'

        // make sure all bin/*.sh files are executable
        shell.ls(appDir.bin) {
            include(name: 'app')
        }.each { shell.chmodPlusX(it) }

        log.info "Install complete."
    }

    def configure = {
        log.info "Configure..."

        // setting up a timer to monitor the server
        timers.schedule(timer: serverMonitor, repeatFrequency: params.serverMonitorFrequency ?: '15s')

        log.info "Configure completed!"
    }

    def start = {
        log.info "Start..."

        def port = params.port

        log.info "port: $port"
        log.info "log file: ${logFile.file.absolutePath}"
        log.info "app: ${appFile.file.absolutePath}"

        shell.exec("${appFile.file.absolutePath} ${port} > ${logFile.file.absolutePath} 2>&1 &\necho \$! > ${pidFile.file.absolutePath}")
    }

    def stop = {
        log.info "Stop..."

        if(isProcessDown()){
            log.info "Server already down."
        } else {
            def pid = shell.exec("cat ${pidFile.file.absolutePath}")
            // invoke the kill command
            shell.exec("kill -15 $pid")

            // we wait for the process to be stopped
            shell.waitFor(timeout: '30s', heartbeat: '1s') { duration ->
                log.info "${duration}: Waiting for server to be down"
                isProcessDown()
            }
        }

        log.info "Stop completed"
    }

    def unconfigure = {
        log.info "Unconfigure..."

        timers.cancel(timer: serverMonitor)

        log.info "Unconfiguration complete."
    }

    def uninstall = {
        log.info "Uninstall..."
        log.info "Do nothing"
        log.info "Uninstall completed"
    }

}
