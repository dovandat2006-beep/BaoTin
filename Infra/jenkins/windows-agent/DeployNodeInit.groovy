import jenkins.model.Jenkins

// Pin the installed operator-reviewed guard source before loading plugin classes.
def j=Jenkins.get()
try {
    def source=new File(j.rootDir,'baotin-deploy-node-guard.groovy')
    if(!source.isFile() || java.nio.file.Files.isSymbolicLink(source.toPath()) || source.length()>32768)
        throw new IllegalStateException('Deploy guard source missing')
    def hash=java.security.MessageDigest.getInstance('SHA-256').digest(source.bytes).encodeHex().toString()
    if(hash!='__GUARD_SHA256__') throw new IllegalStateException('Deploy guard source changed')
    new GroovyShell(j.pluginManager.uberClassLoader).evaluate(source)
} catch(Throwable ignored) {
    // Failure cannot turn the agent into an unguarded one-executor Windows shell.
    def node=j.getNode('baotin-deploy-desktop')
    if(node!=null){node.setNumExecutors(0);node.save()}
    throw new IllegalStateException('Deploy node disabled because its reviewed queue guard failed')
}
return null
