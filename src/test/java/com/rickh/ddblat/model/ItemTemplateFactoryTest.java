package com.rickh.ddblat.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import java.util.Map;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class ItemTemplateFactoryTest {

    private final ItemTemplateFactory factory = new ItemTemplateFactory(42L);

    @Test
    void everyItemHasExactlyTwentyAttributes() {
        for (int j = 0; j < ItemSizeModel.TEMPLATE_COUNT; j += 17) {
            assertThat(factory.itemFor("K#00000001", j)).hasSize(20);
        }
    }

    @Test
    void computedItemSizeMatchesTheSizeModelForEveryTemplate() {
        for (int j = 0; j < ItemSizeModel.TEMPLATE_COUNT; j++) {
            Map<String, AttributeValue> item = factory.itemFor("K#00000001", j);
            assertThat(ItemTemplateFactory.computeItemSizeBytes(item))
                .as("template %d", j)
                .isEqualTo(ItemSizeModel.sizeForTemplate(j))
                .isEqualTo(60_416);
        }
    }

    @Test
    void blobsUseOnlyTheAlphanumericAlphabet() {
        Pattern alnum = Pattern.compile("^[A-Za-z0-9]+$");
        Map<String, AttributeValue> item = factory.itemFor("K#00000001", 128);
        for (int b = 0; b < 5; b++) {
            String v = item.get("blob" + b).s();
            assertThat(v).as("blob%d", b).matches(alnum);
        }
    }

    @Test
    void blobLengthsSumToThePayloadBudget() {
        int j = 128;
        Map<String, AttributeValue> item = factory.itemFor("K#00000001", j);
        int sum = 0;
        for (int b = 0; b < 5; b++) sum += item.get("blob" + b).s().length();
        assertThat(sum)
            .isEqualTo(ItemSizeModel.blobPayloadBytes(ItemSizeModel.sizeForTemplate(j)))
            .isEqualTo(60_194);
    }

    @Test
    void templatesArePrebuiltAndReturnTheSameInstance() {
        assertThat(factory.template(9)).isSameAs(factory.template(9));
    }

    @Test
    void itemsShareBlobReferencesWithTheirTemplateSoTheHotPathAllocatesLittle() {
        AttributeValue fromTemplate = factory.template(9).get("blob0");
        AttributeValue fromItem = factory.itemFor("K#00000001", 9).get("blob0");
        assertThat(fromItem).isSameAs(fromTemplate);
    }

    @Test
    void onlyThePartitionKeyDiffersBetweenTwoItemsOfTheSameTemplate() {
        Map<String, AttributeValue> a = factory.itemFor("K#00000001", 55);
        Map<String, AttributeValue> b = factory.itemFor("K#00000002", 55);
        assertThat(a.get("pk").s()).isEqualTo("K#00000001");
        assertThat(b.get("pk").s()).isEqualTo("K#00000002");
        for (String name : a.keySet()) {
            if (name.equals("pk")) continue;
            assertThat(b.get(name)).as(name).isSameAs(a.get(name));
        }
    }

    @Test
    void numericAttributesUseTheExactDigitWidthsTheOverheadModelAssumes() {
        Map<String, AttributeValue> item = factory.itemFor("K#00000001", 0);
        assertThat(item.get("created").n()).hasSize(13);
        assertThat(item.get("updated").n()).hasSize(13);
        assertThat(item.get("seq").n()).hasSize(7);
        assertThat(item.get("score").n()).hasSize(6);
        assertThat(item.get("weight").n()).hasSize(6);
        assertThat(item.get("flags").n()).hasSize(4);
        assertThat(item.get("shard").n()).hasSize(3);
    }

    @Test
    void factoryIsDeterministicForAGivenSeed() {
        ItemTemplateFactory other = new ItemTemplateFactory(42L);
        assertThat(other.template(77).get("blob2").s())
            .isEqualTo(factory.template(77).get("blob2").s());
    }
}
