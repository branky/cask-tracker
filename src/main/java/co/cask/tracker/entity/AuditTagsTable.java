/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tracker.entity;

import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.lib.AbstractDataset;
import co.cask.cdap.api.dataset.module.EmbeddedDataset;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.common.BadRequestException;
import co.cask.cdap.common.NotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import co.cask.tracker.utils.DiscoveryMetadataClient;
import com.google.common.base.CharMatcher;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 *  Table dataset holding the preferred and the user tags
 */
public final class AuditTagsTable extends AbstractDataset {


  private final Table preferredTagsTable;
  private static final byte[] TOTAL_ENTITIES = Bytes.toBytes("total_entities");
  private static final byte[] DEFAULT_TOTAL_ENTITIES = Bytes.toBytes(0);
  private static final int MAX_TAG_LENGTH = 50;


  private static final CharMatcher TAG_MATCHER = CharMatcher.inRange('A', 'Z')
    .or(CharMatcher.inRange('a', 'z'))
    .or(CharMatcher.inRange('0', '9'))
    .or(CharMatcher.is('_'))
    .or(CharMatcher.is('-'));


  public AuditTagsTable(DatasetSpecification spec, @EmbeddedDataset("preferredTagsTable") Table preferredTagsTable) {
    super(spec.getName(), preferredTagsTable);
    this.preferredTagsTable = preferredTagsTable;
  }

  public TagsResult getUserTags(DiscoveryMetadataClient discoveryMetadataClient, String prefix, NamespaceId namespace)
    throws IOException, UnauthenticatedException, NotFoundException, BadRequestException, UnauthorizedException {
    Map<String, Integer> tagMap = new HashMap<>();
    Set<String> userSet = discoveryMetadataClient.getTags(namespace);
    for (String usertag : userSet) {
      if (preferredTagsTable.get(usertag.getBytes()).isEmpty()) {
        if (usertag.toLowerCase().startsWith(prefix.toLowerCase())) {
          tagMap.put(usertag, discoveryMetadataClient.getEntityNum(usertag, namespace));
        }
      }
    }
    TagsResult result = new TagsResult();
    result.setUserSize(tagMap.size());
    result.setUserTags(tagMap);
    return result;
  }


  public TagsResult getPreferredTags(DiscoveryMetadataClient discoveryMetadataClient, String prefix,
                                     NamespaceId namespace)
    throws IOException, NotFoundException, UnauthenticatedException, BadRequestException, UnauthorizedException {
    Map<String, Integer> tagMap = new HashMap<>();
    try (Scanner scanner = preferredTagsTable.scan(null, null)) {
      Row row;
      while ((row = scanner.next()) != null) {
        String tag = Bytes.toString(row.getRow());
        if (tag.toLowerCase().startsWith(prefix.toLowerCase())) {
          tagMap.put(tag, discoveryMetadataClient.getEntityNum(tag, namespace));
        }
      }
    }
    TagsResult result = new TagsResult();
    result.setPreferredSize(tagMap.size());
    result.setPreferredTags(tagMap);
    return result;
  }


  public TagsResult getTags(DiscoveryMetadataClient discoveryMetadataClient, String prefix, NamespaceId namespace)
    throws IOException, NotFoundException, UnauthenticatedException, BadRequestException, UnauthorizedException {
    TagsResult userResult = getUserTags(discoveryMetadataClient, prefix, namespace);
    TagsResult preferredResult = getPreferredTags(discoveryMetadataClient, prefix, namespace);
    preferredResult.setUserSize(userResult.getUserSize());
    preferredResult.setUserTags(userResult.getUserTags());
    return preferredResult;
  }



  public TagsResult getEntityTags(DiscoveryMetadataClient discoveryMetadataClient, NamespaceId namespace,
                                  String entityType, String entityName)
    throws UnauthenticatedException, BadRequestException, NotFoundException, IOException, UnauthorizedException {
    Map<String, Integer> pTagMap = new HashMap<>();
    Map<String, Integer> uTagMap = new HashMap<>();
    Set<String> userSet = discoveryMetadataClient.getEntityTags(namespace, entityType, entityName);
    for (String tag : userSet) {
      if (preferredTagsTable.get(tag.getBytes()).isEmpty()) {
        uTagMap.put(tag, discoveryMetadataClient.getEntityNum(tag, namespace));
      } else {
        pTagMap.put(tag, discoveryMetadataClient.getEntityNum(tag, namespace));
      }
    }
    TagsResult result = new TagsResult();
    result.setUserSize(uTagMap.size());
    result.setUserTags(uTagMap);
    result.setPreferredSize(pTagMap.size());
    result.setPreferredTags(pTagMap);
    return result;
  }


  public ValidateTagsResult demoteTag(List<String> tagList) {
    List<String> valid = new LinkedList<>();
    List<String> invalid = new LinkedList<>();
    for (String tag : tagList) {
      Row row = preferredTagsTable.get(tag.getBytes());
      if (!row.isEmpty()) {
        preferredTagsTable.delete(tag.getBytes());
        valid.add(tag);
      } else {
        invalid.add(tag);
      }
    }
    return new ValidateTagsResult(valid, invalid);
  }


  public boolean deleteTag(String tag) {
    if (!preferredTagsTable.get(tag.getBytes()).isEmpty()) {
      preferredTagsTable.delete(tag.getBytes());
      return true;
    }
    return false;
  }


  public ValidateTagsResult addPreferredTags(List<String> tagList) {
    List<String> valid = new LinkedList<>();
    List<String> invalid = new LinkedList<>();
    for (String tag : tagList) {
      if (isValid(tag)) {
        valid.add(tag);
        preferredTagsTable.put(tag.getBytes(), TOTAL_ENTITIES, DEFAULT_TOTAL_ENTITIES);
      } else {
        invalid.add(tag);
      }
    }
    return new ValidateTagsResult(valid, invalid);
  }


  public ValidateTagsResult validateTags (List<String> tagList) {
    List<String> validList = new LinkedList<>();
    List<String> invalidList = new LinkedList<>();
    for (String tag : tagList) {
      if (isValid(tag)) {
        validList.add(tag);
      } else {
        invalidList.add(tag);
      }
    }
    return new ValidateTagsResult(validList, invalidList);
  }

  private boolean isValid(String tag) {
    return !(!TAG_MATCHER.matchesAllOf(tag) || tag.length() > MAX_TAG_LENGTH);
  }
}
