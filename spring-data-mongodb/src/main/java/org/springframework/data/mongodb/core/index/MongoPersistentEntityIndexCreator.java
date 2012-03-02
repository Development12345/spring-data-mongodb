/*
 * Copyright (c) 2011 by the original author(s).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.mongodb.core.index;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.event.MappingContextEvent;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.mapping.BasicMongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;

/**
 * Component that inspects {@link BasicMongoPersistentEntity} instances contained in the given
 * {@link MongoMappingContext} for indexing metadata and ensures the indexes to be available.
 * 
 * @author Jon Brisbin <jbrisbin@vmware.com>
 * @author Oliver Gierke
 */
public class MongoPersistentEntityIndexCreator implements
		ApplicationListener<MappingContextEvent<MongoPersistentEntity<MongoPersistentProperty>, MongoPersistentProperty>> {

	private static final Log log = LogFactory.getLog(MongoPersistentEntityIndexCreator.class);

	private final Map<Class<?>, Boolean> classesSeen = new ConcurrentHashMap<Class<?>, Boolean>();
	private final MongoDbFactory mongoDbFactory;

	/**
	 * Creats a new {@link MongoPersistentEntityIndexCreator} for the given {@link MongoMappingContext} and
	 * {@link MongoDbFactory}.
	 * 
	 * @param mappingContext must not be {@@iteral null}
	 * @param mongoDbFactory must not be {@@iteral null}
	 */
	public MongoPersistentEntityIndexCreator(MongoMappingContext mappingContext, MongoDbFactory mongoDbFactory) {

		Assert.notNull(mongoDbFactory);
		Assert.notNull(mappingContext);
		this.mongoDbFactory = mongoDbFactory;

		for (MongoPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
			checkForIndexes(entity);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
	 */
	public void onApplicationEvent(
			MappingContextEvent<MongoPersistentEntity<MongoPersistentProperty>, MongoPersistentProperty> event) {

		PersistentEntity<?, ?> entity = event.getPersistentEntity();

		// Double check type as Spring infrastructure does not consider nested generics
		if (entity instanceof MongoPersistentEntity) {
			checkForIndexes(event.getPersistentEntity());
		}
	}

	protected void checkForIndexes(final MongoPersistentEntity<?> entity) {
		final Class<?> type = entity.getType();
		if (!classesSeen.containsKey(type)) {
			if (log.isDebugEnabled()) {
				log.debug("Analyzing class " + type + " for index information.");
			}

			// Make sure indexes get created
			if (type.isAnnotationPresent(CompoundIndexes.class)) {
				CompoundIndexes indexes = type.getAnnotation(CompoundIndexes.class);
				for (CompoundIndex index : indexes.value()) {
					String indexColl = index.collection();
					if ("".equals(indexColl)) {
						indexColl = entity.getCollection();
					}
					ensureIndex(indexColl, index.name(), index.def(), index.direction(), index.unique(), index.dropDups(),
							index.sparse());
					if (log.isDebugEnabled()) {
						log.debug("Created compound index " + index);
					}
				}
			}

			entity.doWithProperties(new PropertyHandler<MongoPersistentProperty>() {
				public void doWithPersistentProperty(MongoPersistentProperty persistentProperty) {
					Field field = persistentProperty.getField();
					if (field.isAnnotationPresent(Indexed.class)) {
						Indexed index = field.getAnnotation(Indexed.class);
						String name = index.name();
						if (!StringUtils.hasText(name)) {
							name = persistentProperty.getFieldName();
						} else {
							if (!name.equals(field.getName()) && index.unique() && !index.sparse()) {
								// Names don't match, and sparse is not true. This situation will generate an error on the server.
								if (log.isWarnEnabled()) {
									log.warn("The index name " + name + " doesn't match this property name: " + field.getName()
											+ ". Setting sparse=true on this index will prevent errors when inserting documents.");
								}
							}
						}
						String collection = StringUtils.hasText(index.collection()) ? index.collection() : entity.getCollection();
						ensureIndex(collection, name, null, index.direction(), index.unique(), index.dropDups(), index.sparse());
						if (log.isDebugEnabled()) {
							log.debug("Created property index " + index);
						}
					} else if (field.isAnnotationPresent(GeoSpatialIndexed.class)) {

						GeoSpatialIndexed index = field.getAnnotation(GeoSpatialIndexed.class);

						GeospatialIndex indexObject = new GeospatialIndex(persistentProperty.getFieldName());
						indexObject.withMin(index.min()).withMax(index.max());
						indexObject.named(StringUtils.hasText(index.name()) ? index.name() : field.getName());

						String collection = StringUtils.hasText(index.collection()) ? index.collection() : entity.getCollection();
						mongoDbFactory.getDb().getCollection(collection)
								.ensureIndex(indexObject.getIndexKeys(), indexObject.getIndexOptions());

						if (log.isDebugEnabled()) {
							log.debug(String.format("Created %s for entity %s in collection %s! ", indexObject, entity.getType(),
									collection));
						}
					}
				}
			});

			classesSeen.put(type, true);
		}
	}

	protected void ensureIndex(String collection, final String name, final String def, final IndexDirection direction,
			final boolean unique, final boolean dropDups, final boolean sparse) {
		DBObject defObj;
		if (null != def) {
			defObj = (DBObject) JSON.parse(def);
		} else {
			defObj = new BasicDBObject();
			defObj.put(name, (direction == IndexDirection.ASCENDING ? 1 : -1));
		}
		DBObject opts = new BasicDBObject();
		opts.put("name", name);
		opts.put("dropDups", dropDups);
		opts.put("sparse", sparse);
		opts.put("unique", unique);
		mongoDbFactory.getDb().getCollection(collection).ensureIndex(defObj, opts);
	}

}
