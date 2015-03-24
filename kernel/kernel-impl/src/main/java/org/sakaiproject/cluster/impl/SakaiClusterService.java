/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2004, 2005, 2006, 2007, 2008 Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.opensource.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.cluster.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.cluster.api.ClusterService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.event.api.Event;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.thread_local.api.ThreadLocalManager;

/**
 * <p>
 * SakaiClusterService is a Sakai cluster service implementation.
 * This class is it just manages it's own row in the DB and events are used to pass notifications to a node.
 * </p>
 */
public class SakaiClusterService implements ClusterService
{
	/** Our log (commons). */
	private static Log M_log = LogFactory.getLog(SakaiClusterService.class);

	/** The maintenance. */
	protected Maintenance m_maintenance = null;

	/** Our status */
	protected Status status = Status.UNKNOWN;

	/*************************************************************************************************************************************************
	 * Dependencies and their setter methods
	 ************************************************************************************************************************************************/

	/** Dependency: ServerConfigurationService. */
	protected ServerConfigurationService m_serverConfigurationService = null;

	/**
	 * Dependency: ServerConfigurationService.
	 *
	 * @param service
	 *        The ServerConfigurationService.
	 */
	public void setServerConfigurationService(ServerConfigurationService service)
	{
		m_serverConfigurationService = service;
	}

	/** Dependency: EventTrackingService. */
	protected EventTrackingService m_eventTrackingService = null;

	/**
	 * Dependency: EventTrackingService.
	 *
	 * @param service
	 *        The EventTrackingService.
	 */
	public void setEventTrackingService(EventTrackingService service)
	{
		m_eventTrackingService = service;
	}

	/** Dependency: SqlService. */
	protected SqlService m_sqlService = null;

	/**
	 * Dependency: SqlService.
	 *
	 * @param service
	 *        The SqlService.
	 */
	public void setSqlService(SqlService service)
	{
		m_sqlService = service;
	}

	/** Dependency: UsageSessionService. */
	protected UsageSessionService m_usageSessionService = null;

	/**
	 * Dependency: UsageSessionService.
	 *
	 * @param service
	 *        The UsageSessionService.
	 */
	public void setUsageSessionService(UsageSessionService service)
	{
		m_usageSessionService = service;
	}

	/** Configuration: how often to register that we are alive with the cluster table (seconds). */
	protected long m_refresh = 60;

	/**
	 * Configuration: set the refresh value
	 *
	 * @param value
	 *        The refresh value.
	 */
	public void setRefresh(String value)
	{
		try
		{
			m_refresh = Long.parseLong(value);
		}
		catch (Exception ignore)
		{
		}
	}

	/** Configuration: how long we give an app server to respond before it is considered lost (seconds). */
	protected long m_expired = 600;

	/**
	 * Configuration: set the expired value
	 *
	 * @param value
	 *        The expired value.
	 */
	public void setExpired(String value)
	{
		try
		{
			m_expired = Long.parseLong(value);
		}
		catch (Exception ignore)
		{
		}
	}

	/** Configuration: to run the ddl on init or not. */
	protected boolean m_autoDdl = false;

	/**
	 * Configuration: to run the ddl on init or not.
	 *
	 * @param value
	 *        the auto ddl value.
	 */
	public void setAutoDdl(String value)
	{
		m_autoDdl = Boolean.valueOf(value).booleanValue();
	}

	/** Dependency: the current manager. */
	protected ThreadLocalManager m_threadLocalManager = null;

	/**
	 * Dependency - set the current manager.
	 *
	 * @param value
	 *        The current manager.
	 */
	public void setThreadLocalManager(ThreadLocalManager manager)
	{
		m_threadLocalManager = manager;
	}

	/** Configuration: percent of maintenance passes to run the full de-ghosting / cleanup activities. */
	protected int m_ghostingPercent = 100;

	/**
	 * Configuration: set the percent of maintenance passes to run the full de-ghosting / cleanup activities
	 *
	 * @param value
	 *        The percent of maintenance passes to run the full de-ghosting / cleanup activities.
	 */
	public void setGhostingPercent(String value)
	{
		try
		{
			m_ghostingPercent = Integer.parseInt(value);
		}
		catch (Exception ignore)
		{
		}
	}

	public void setDatabaseBeans(Map databaseBeans)
	{
		this.databaseBeans = databaseBeans;
	}

	/** contains a map of the database dependent handlers. */
	protected Map<String, ClusterServiceSql> databaseBeans;

	/** the handler we are using. */
	protected ClusterServiceSql clusterServiceSql;

	public ClusterServiceSql getClusterServiceSql()
	{
		return clusterServiceSql;
	}

	/**
	 * sets which bean containing database dependent code should be used depending on the database vendor.
	 */
	public void setClusterServiceSql(String vendor)
	{
		this.clusterServiceSql = (databaseBeans.containsKey(vendor) ? databaseBeans.get(vendor) : databaseBeans.get("default"));
	}

	/*************************************************************************************************************************************************
	 * Init and Destroy
	 ************************************************************************************************************************************************/

	/**
	 * Final initialization, once all dependencies are set.
	 */
	public void init()
	{
		changeStatus(Status.STARTING);
		m_eventTrackingService.addObserver(new ClusterEventObserver());
		setClusterServiceSql(m_sqlService.getVendor());
		try
		{
			// if we are auto-creating our schema, check and create
			if (m_autoDdl)
			{
				m_sqlService.ddl(this.getClass().getClassLoader(), "sakai_cluster");
			}

			// start the maintenance thread
			m_maintenance = new Maintenance();
			m_maintenance.start();

			M_log.info("init: refresh: " + m_refresh + " expired: " + m_expired + " ghostingPercent: " + m_ghostingPercent);
		}
		catch (Exception t)
		{
			M_log.warn("init(): ", t);
		}
	}

	/**
	 * Returns to uninitialized state.
	 */
	public void destroy()
	{
		changeStatus(Status.STOPPING);
		m_maintenance.stop();
		m_maintenance = null;

		M_log.info("destroy()");
	}


	/*************************************************************************************************************************************************
	 * ClusterService implementation
	 ************************************************************************************************************************************************/

	@Override
	public Status getStatus()
	{
		return status;
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<String> getServers()
	{
		String statement = clusterServiceSql.getListServersSql();
		List<String> servers = m_sqlService.dbRead(statement);

		return servers;
	}

	@Override
	public Map<String, Status> getServerStatus()
	{
		String statement = clusterServiceSql.getListServerStatusSql();
		final Map<String, Status> servers = new HashMap<String, Status>();
		m_sqlService.dbRead(statement, null, new SqlReader()
		{
			@Override
			public Object readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException
			{
				try
				{
					String serverId = result.getString("SERVER_ID");
					Status status = parseStatus(result.getString("STATUS"));
					// Happy to put null into status? or should we convert to UNKNOWN?
					servers.put(serverId, status);
				}
				catch (SQLException e)
				{
					M_log.warn("Failed to read result.", e);
				}
				return null;
			}
		});
		// Always override DB status with memory version.
		Status dbStatus = servers.put(m_serverConfigurationService.getServerIdInstance(), status);
		if (dbStatus == null)
		{
			M_log.warn("Failed to find ourselves in the cluster: "+ m_serverConfigurationService.getServerIdInstance());
		}
		else if (!status.equals(dbStatus))
		{
			M_log.warn("In memory status ("+ status+ ") different to DB ("+ dbStatus+ ")");
		}
		return servers;
	}

	@Override
	public void markClosing(String serverId, boolean close)
	{
		if(!(getServers().contains(serverId)))
		{
			throw new IllegalArgumentException("Unknown server ID: "+ serverId);
		}
		String event = (close)? EVENT_CLOSE :EVENT_RUN;
		m_eventTrackingService.post(m_eventTrackingService.newEvent(event, serverId, true));
	}

	/**
	 * This changes the status of the current server.
	 * @param status The new status.
	 */
	protected void changeStatus(Status status)
	{
		if (status != null && !(this.status.equals(status)))
		{
			M_log.info("Switching status from "+ this.status+ " to "+ status);
			this.status = status;
			if (m_maintenance != null)
			{
				m_maintenance.update();
			}
		}
	}

	/*************************************************************************************************************************************************
	 * Maintenance
	 ************************************************************************************************************************************************/

	protected class Maintenance implements Runnable
	{
		/** My thread running my timeout checker. */
		protected Thread m_maintenanceChecker = null;

		/** Signal to the timeout checker to stop. */
		protected boolean m_maintenanceCheckerStop = false;

		/** Out of sync update of status. */
		protected boolean m_updateStatus = false;

		/**
		 * Construct.
		 */
		public Maintenance()
		{
		}

		/**
		 * Start the maintenance thread, registering this app server in the cluster table.
		 */
		public void start()
		{
			if (m_maintenanceChecker != null) return;

			// register in the cluster table
			String statement = clusterServiceSql.getInsertServerSql();
			Object fields[] = new Object[2];
			fields[0] = m_serverConfigurationService.getServerIdInstance();
			fields[1] = Status.STARTING.toString();

			boolean ok = m_sqlService.dbWrite(statement, fields);
			if (!ok)
			{
				M_log.warn("start(): dbWrite failed");
			}

			m_maintenanceChecker = new Thread(this, "SakaiClusterService.Maintenance");
			m_maintenanceChecker.setDaemon(true);
			m_maintenanceCheckerStop = false;
			m_maintenanceChecker.start();
		}

		/**
		 * Stop the maintenance thread, removing this app server's registration from the cluster table.
		 */
		public void stop()
		{
			if (m_maintenanceChecker != null)
			{
				m_maintenanceCheckerStop = true;
				m_maintenanceChecker.interrupt();
				try
				{
					// wait for it to die
					m_maintenanceChecker.join();
				}
				catch (InterruptedException ignore)
				{
				}
				m_maintenanceChecker = null;
			}

			// close our entry from the database - delete the record
			String statement = clusterServiceSql.getDeleteServerSql();
			Object fields[] = new Object[1];
			fields[0] = m_serverConfigurationService.getServerIdInstance();
			boolean ok = m_sqlService.dbWrite(statement, fields);
			if (!ok)
			{
				M_log.warn("stop(): dbWrite failed: " + statement);
			}
		}

		/**
		 * Update the status in the DB.
		 */
		public void update()
		{
			if (m_maintenanceChecker == null)
			{
				return;
			}
			m_updateStatus = true;
			m_maintenanceChecker.interrupt();
		}

		/**
		 * Run the maintenance thread. Every REFRESH seconds, re-register this app server as alive in the cluster. Then check for any cluster entries
		 * that are more than EXPIRED seconds old, indicating a failed app server, and remove that record, that server's sessions,
		 * generating appropriate session events so the other app servers know what's going on. The "then" checks need not be done each
		 * iteration - run them on 1 of n randomly choosen iterations. In a clustered environment, this also distributes the work over the cluster
		 * better.
		 */
		public void run()
		{
			// wait till things are rolling
			ComponentManager.waitTillConfigured();
			// Component manager is up so now we update our status.
			status = Status.RUNNING;
			if (M_log.isDebugEnabled()) M_log.debug("run()");

			while (!m_maintenanceCheckerStop)
			{
				final String serverIdInstance = m_serverConfigurationService.getServerIdInstance();
				try
				{

					updateOurStatus(serverIdInstance);
					ghostCleanup(serverIdInstance);


				}
				catch (Exception e)
				{
					M_log.warn("exception: ", e);
				}
				finally
				{
					// clear out any current access bindings
					m_threadLocalManager.clear();
				}

				// cycle every REFRESH seconds
				if (!m_maintenanceCheckerStop)
				{
					try
					{
						long sleepTill = System.currentTimeMillis() + m_refresh * 1000L;
						long sleepFor = sleepTill - System.currentTimeMillis();
						while (sleepFor > 0)
						{
							try
							{
								Thread.sleep(sleepFor);
								sleepFor = sleepTill - System.currentTimeMillis();
							}
							catch (InterruptedException e)
							{
								if (m_updateStatus)
								{
									updateOurStatus(serverIdInstance);
									m_updateStatus = false;
								}
								if(!m_updateStatus || m_maintenanceCheckerStop)
								{
									throw e;
								}
							}
						}
					}
					catch (Exception ignore)
					{
					}
				}
			}

			if (M_log.isDebugEnabled()) M_log.debug("done");
		}

		private void ghostCleanup(String serverIdInstance)
		{
			// pick a random number, 0..99, to see if we want to do the full ghosting / cleanup activities now
			int rand = (int) (Math.random() * 100.0);
			if (rand < m_ghostingPercent)
			{
				String statement;

				// get all expired open app servers not me
				statement = clusterServiceSql.getListExpiredServers(m_expired);
				// setup the fields to skip reading me!
				Object[] fields = new Object[1];
				fields[0] = serverIdInstance;

				List instances = m_sqlService.dbRead(statement, fields, null);

				// close any severs found to be expired
				for (Iterator iInstances = instances.iterator(); iInstances.hasNext();)
				{
					String serverId = (String) iInstances.next();

					// close the server - delete the record
					statement = clusterServiceSql.getDeleteServerSql();
					fields[0] = serverId;
					boolean ok = m_sqlService.dbWrite(statement, fields);
					if (!ok)
					{
						M_log.warn("run(): dbWrite failed: " + statement);
					}

					M_log.warn("run(): ghost-busting server: " + serverId + " from : " + serverIdInstance);
				}

				// Close all sessions left over from deleted servers.
				int nbrClosed = m_usageSessionService.closeSessionsOnInvalidServers(getServers());
				if ((nbrClosed > 0) && M_log.isInfoEnabled()) M_log.info("Closed " + nbrClosed + " orphaned usage session records");

				// Delete any orphaned locks from closed or missing sessions.
				statement = clusterServiceSql.getOrphanedLockSessionsSql();
				List sessions =  m_sqlService.dbRead(statement);
				if (sessions.size() > 0) {
					if (M_log.isInfoEnabled()) M_log.info("Found " + sessions.size() + " closed or deleted sessions in lock table");
					statement = clusterServiceSql.getDeleteLocksSql();
					for (Iterator iSessions = sessions.iterator(); iSessions.hasNext();)
					{
						fields[0] = (String) iSessions.next();
						boolean ok = m_sqlService.dbWrite(statement, fields);
						if (!ok)
						{
							M_log.warn("run(): dbWrite failed: " + statement);
						}
					}
				}
			}
		}

		private void updateOurStatus(String serverIdInstance)
		{
			if (M_log.isDebugEnabled()) M_log.debug("checking...");

			// if we have been closed, reopen!
			String statement = clusterServiceSql.getReadServerSql();
			Object[] fields = new Object[1];
			fields[0] = serverIdInstance;
			List results = m_sqlService.dbRead(statement, fields, new StatusSqlReader());
			if (results.isEmpty())
			{
				M_log.warn("run(): server has been closed in cluster table, reopened: " + serverIdInstance);

				statement = clusterServiceSql.getInsertServerSql();
				fields = new Object[2];
				fields[0] = serverIdInstance;
				fields[1] = status;
				boolean ok = m_sqlService.dbWrite(statement, fields);
				if (!ok)
				{
					M_log.warn("start(): dbWrite failed");
				}
			}

			// update our alive and well status
			else
			{
				// register that this app server is alive and well
				statement = clusterServiceSql.getUpdateServerSql();
				fields = new Object[2];
				fields[0] = status;
				fields[1] = serverIdInstance;
				boolean ok = m_sqlService.dbWrite(statement, fields);
				if (!ok)
				{
					M_log.warn("run(): dbWrite failed: " + statement);
				}
			}
		}

		/**
		 * Reads a status row from the DB.
		 */
		private class StatusSqlReader implements SqlReader
		{
			@Override
			public Object readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException
			{
				Status status = null;
				try
				{
					status = parseStatus(result.getString("STATUS"));
				}
				catch (SQLException sqlException)
				{
					M_log.warn("Failed to read STATUS.", sqlException);
				}
				return status;
			}
		}
	}

	private Status parseStatus(String statusString)
	{
		Status status = null;
		if (statusString != null)
		{
			try
			{
				status = Status.valueOf(statusString);
			}
			catch (IllegalArgumentException iae)
			{
				M_log.debug("Failed to convert to a status: "+ statusString);
			}
		}
		return status;
	}

	/**
	 * This watches for events asking us to change status.
	 */
	private class ClusterEventObserver implements Observer
	{

		@Override
		public void update(Observable o, Object arg)
		{
			if (arg instanceof Event)
			{
				Event event = ((Event) arg);
				// We don't actually
				if (m_serverConfigurationService.getServerIdInstance().equals(event.getResource()))
				{
					if (EVENT_CLOSE.equals(event.getEvent()))
					{
						changeStatus(Status.CLOSING);
					}
					else if (EVENT_RUN.equals(event.getEvent()))
					{
						changeStatus(Status.RUNNING);
					}
				}
				else
				{
					M_log.debug("Ignoring message for other node.");
				}
			}
		}
	}
}
