package org.tmatesoft.svn.core.wc;

import org.tmatesoft.svn.core.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.SVNAuthentication;

/**
 * Created by IntelliJ IDEA.
 * User: alex
 * Date: 22.06.2005
 * Time: 21:33:56
 * To change this template use File | Settings | File Templates.
 */
public interface ISVNAuthenticationProvider {

    public int REJECTED = 0;
    public int ACCEPTED_TEMPORARY = 1;
    public int ACCEPTED = 2;

    // null for cancellation.
    public SVNAuthentication requestClientAuthentication(String kind, String realm, SVNAuthentication previousAuth, ISVNAuthenticationManager manager);

    public int acceptServerAuthentication(SVNAuthentication authentication, ISVNAuthenticationManager manager);
}
